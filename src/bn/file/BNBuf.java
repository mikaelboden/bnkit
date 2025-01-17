/*
    bnkit -- software for building and using Bayesian networks
    Copyright (C) 2014  M. Boden et al.

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package bn.file;

import dat.Variable;
import bn.*;
import bn.alg.CGVarElim;

import java.io.File;
import java.io.IOException;
import java.util.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Class that defines methods for loading and saving Bayesian networks to and from files, on the XML format.
 * @author mikael
 */
public class BNBuf {

	public static BNet loadJSON(String json_string) {
        throw new RuntimeException("Not yet implemented");
    }

    /**
     * Load the network including definitions of variables and nodes.
     * @param filename the name of the file
     * @return a new BNet structure.
     */
    public static BNet load(String filename) {
        Document document;
        File file;
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            file = new File(filename);
            document = builder.parse(file);
        } catch (SAXParseException spe) {
            // Error generated by the parser
            System.out.println("\n** Parsing error" + ", line " + spe.getLineNumber() + ", uri " + spe.getSystemId());
            System.out.println("   " + spe.getMessage());
            // Use the contained exception, if any
            Exception x = spe;
            if (spe.getException() != null) {
                x = spe.getException();
            }
            x.printStackTrace();
            return null;
        } catch (SAXException sxe) {
            // Error generated during parsing)
            Exception x = sxe;
            if (sxe.getException() != null) {
                x = sxe.getException();
            }
            x.printStackTrace();
            return null;
        } catch (ParserConfigurationException pce) {
            // Parser with specified options can't be built
            pce.printStackTrace();
            return null;
        } catch (IOException ioe) {
            // I/O error
            ioe.printStackTrace();
            return null;
        }
        NodeList bn_nodes = document.getChildNodes();
        for (int b = 0; b < bn_nodes.getLength(); b++) {

            NodeList n = bn_nodes.item(b).getChildNodes();
            BNet bn = new BNet();
            Map<String, Variable> all_vars = new HashMap<String, Variable>();
            for (int i = 0; i < n.getLength(); i++) {
                Node node = n.item(i);
                String nodeName = node.getNodeName();
                if (nodeName.equalsIgnoreCase("def")) { // definition
                    NodeList nn = node.getChildNodes();
                    for (int j = 0; j < nn.getLength(); j++) {
                        Node var_element = nn.item(j);
                        if (var_element.getNodeName().equalsIgnoreCase("var")) { // variable
                            NamedNodeMap var_atts = var_element.getAttributes();
                            if (var_atts != null) {
                                Variable var = null;
                                Node var_name = var_atts.getNamedItem("name");
                                Node var_type = var_atts.getNamedItem("type");
                                if (var_name == null || var_type == null) {
                                    System.err.println("Variable specification invalid and ignored");
                                } else {
                                    Node var_params = var_atts.getNamedItem("params");
                                    String var_name_str = var_name.getNodeValue();
                                    String var_type_str = var_type.getNodeValue();
                                    if (var_params == null) {
                                        var = Predef.getVariable(var_name_str, var_type_str, null);
                                    } else {
                                        var = Predef.getVariable(var_name_str, var_type_str, var_params.getNodeValue());
                                    }
                                    if (var == null) {
                                        System.err.println("Variable specification invalid and ignored: " + var_name.getNodeValue());
                                    } else {
                                        all_vars.put(var_name_str, var);
                                    }
                                }
                            }
                        }
                    }
                } else if (nodeName.equalsIgnoreCase("node")) {
                    NamedNodeMap node_atts = node.getAttributes();
                    Node node_type = node_atts.getNamedItem("type");
                    Node node_var = node_atts.getNamedItem("var");

                    Node node_trainable = node_atts.getNamedItem("trainable");
                    if (node_var == null) {
                        System.err.println("Node specification invalid and ignored: Missing \"var\" field.");
                        continue;
                    }
                    if (node_type == null) {
                        System.err.println("Node specification invalid and ignored: Missing \"type\" field for variable " + node_var.getNodeValue());
                        continue;
                    }
                    Variable var = all_vars.get(node_var.getNodeValue());
                    if (var != null) {
                        List<Variable> parent_vars = new ArrayList<Variable>();
                        String dump = null;
                        NodeList nn = node.getChildNodes();
                        for (int j = 0; j < nn.getLength(); j++) {
                            Node node_element = nn.item(j);
                            String node_spec = node_element.getNodeName();
                            if (node_spec.equalsIgnoreCase("parent")) {
                                NamedNodeMap parent_atts = node_element.getAttributes();
                                Node parent_var = parent_atts.getNamedItem("var");
                                Variable pvar = all_vars.get(parent_var.getNodeValue());
                                if (pvar != null) {
                                    parent_vars.add(pvar);
                                } else {
                                    System.err.println("Node specification invalid and ignored: " + node_var.getNodeValue());
                                }
                            } else if (node_spec.equalsIgnoreCase("params")) {
                                dump = node_element.getTextContent();
                            }
                        }
                        String type = node_type.getNodeValue();
                        BNode bnode = null;
                        if (type != null) {
                            bnode = Predef.getBNode(var, parent_vars, type);
                        }
                        if (node_trainable != null) {
                        	Boolean trainable = Boolean.valueOf(node_trainable.getNodeValue());
                            if (!trainable) {
                            	bnode.setTrainable(false);
                            }
                        }
                        if (bnode != null) {
                            if (dump != null) {
                                bnode.setState(dump);
                            }
                            bn.add(bnode);
                        } else {
                            System.err.println("Node specification invalid and ignored: " + node_var.getNodeValue());
                        }
                    } else {
                        System.err.println("Node specification invalid and ignored: " + node_var.getNodeValue());
                    }
                } else if (nodeName.equalsIgnoreCase("tag")) { //load tags
                    NamedNodeMap tag_atts = node.getAttributes();
                    Node tag_label = tag_atts.getNamedItem("label");
                    String labelVal = tag_label.getNodeValue();
                    NodeList tn = node.getChildNodes();
                    for (int j = 0; j < tn.getLength(); j++) {
                        Node tag_element = tn.item(j);
                        String tag_spec = tag_element.getNodeName();
                        if (tag_spec.equalsIgnoreCase("variables")) {
                            String[] dump = tag_element.getTextContent().split("\n");
                            List<String> variableNames = Arrays.asList(dump).subList(1,dump.length);
                            for (String name: variableNames){
                                BNode bnode = bn.getNode(name);
                                if (bnode == null){
                                    System.err.println("Nodename " + name + " in tag specification has no matching node");
                                }
                                bn.setTags(labelVal, bn.getNode(name));
                            }
                        }
                    }
                } else if (nodeName.equalsIgnoreCase("tie")) { //load tied specifications
                    NamedNodeMap tie_atts = node.getAttributes();
                    String dep = tie_atts.getNamedItem("dependant").getNodeValue();
                    String source = tie_atts.getNamedItem("source").getNodeValue();
                    ((TiedNode)bn.getNode(dep)).tieTo((TiedNode) bn.getNode(source));
                }
            }
            return bn;
        }
        return null;
    }
    
    /**
     * Load the network but use the current variable definitions in place of those in the file.
     * This performs the same operation as {@see bn.file.BNBuf#load} but ensures that variables are re-used.
     * Will print an error if the variable used is NOT previously defined.
     * @param filename the name of the file
     * @return a new BNet structure, that uses current variable definitions.
     */
    public static BNet reload(String filename) {
        Document document;
        File file;
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            file = new File(filename);
            document = builder.parse(file);
        } catch (SAXParseException spe) {
            // Error generated by the parser
            System.out.println("\n** Parsing error" + ", line " + spe.getLineNumber() + ", uri " + spe.getSystemId());
            System.out.println("   " + spe.getMessage());
            // Use the contained exception, if any
            Exception x = spe;
            if (spe.getException() != null) {
                x = spe.getException();
            }
            x.printStackTrace();
            return null;
        } catch (SAXException sxe) {
            // Error generated during parsing)
            Exception x = sxe;
            if (sxe.getException() != null) {
                x = sxe.getException();
            }
            x.printStackTrace();
            return null;
        } catch (ParserConfigurationException pce) {
            // Parser with specified options can't be built
            pce.printStackTrace();
            return null;
        } catch (IOException ioe) {
            // I/O error
            ioe.printStackTrace();
            return null;
        }
        NodeList bn_nodes = document.getChildNodes();
        for (int b = 0; b < bn_nodes.getLength(); b++) {

            NodeList n = bn_nodes.item(b).getChildNodes();
            BNet bn = new BNet();
            Map<String, Variable> all_vars = new HashMap<String, Variable>();
            for (Variable<?> var : Variable.getAll())
                all_vars.put(var.getName(), var);
            for (int i = 0; i < n.getLength(); i++) {
                Node node = n.item(i);
                String nodeName = node.getNodeName();
                if (nodeName.equalsIgnoreCase("node")) {
                    NamedNodeMap node_atts = node.getAttributes();
                    Node node_type = node_atts.getNamedItem("type");
                    Node node_var = node_atts.getNamedItem("var");
                    Node node_trainable = node_atts.getNamedItem("trainable");
                    if (node_var == null) {
                        System.err.println("Node specification invalid and ignored: Missing \"var\" field.");
                        continue;
                    }
                    if (node_type == null) {
                        System.err.println("Node specification invalid and ignored: Missing \"type\" field for variable " + node_var.getNodeValue());
                        continue;
                    }
                    Variable var = all_vars.get(node_var.getNodeValue());
                    if (var != null) {
                        List<Variable> parent_vars = new ArrayList<Variable>();
                        String dump = null;
                        NodeList nn = node.getChildNodes();
                        for (int j = 0; j < nn.getLength(); j++) {
                            Node node_element = nn.item(j);
                            String node_spec = node_element.getNodeName();
                            if (node_spec.equalsIgnoreCase("parent")) {
                                NamedNodeMap parent_atts = node_element.getAttributes();
                                Node parent_var = parent_atts.getNamedItem("var");
                                Variable pvar = all_vars.get(parent_var.getNodeValue());
                                if (pvar != null) {
                                    parent_vars.add(pvar);
                                } else {
                                    System.err.println("Node specification invalid and ignored: " + node_var.getNodeValue());
                                }
                            } else if (node_spec.equalsIgnoreCase("params")) {
                                dump = node_element.getTextContent();
                            }
                        }
                        String type = node_type.getNodeValue();
                        BNode bnode = null;
                        if (type != null) {
                            bnode = Predef.getBNode(var, parent_vars, type);
                        }
                        if (node_trainable != null) {
                        	Boolean trainable = Boolean.valueOf(node_trainable.getNodeValue());
                            if (!trainable) {
                            	bnode.setTrainable(false);
                            }
                        }
                        if (bnode != null) {
                            if (dump != null) {
                                bnode.setState(dump);
                            }
                            bn.add(bnode);
                        } else {
                            System.err.println("Node specification invalid and ignored: " + node_var.getNodeValue());
                        }
                    } else {
                        System.err.println("Node specification refers to non-existent variable: " + node_var.getNodeValue());
                    }
                }
            }
            return bn;
        }
        return null;
    }

    public static String saveJSON(BNet bn) {
        throw new RuntimeException("Not yet implemented");
    }

    public static boolean save(BNet bn, String filename) {
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder;
        try {
            dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.newDocument();
            //add elements to Document
            Element rootElement = doc.createElement("bnet");
            //append root element to document
            doc.appendChild(rootElement);
            // append definitions
            Element def_element = doc.createElement("def");
            rootElement.appendChild(def_element);
            // append the variables within the definition section
            for (BNode node : bn.getNodes()) {
                Variable var = node.getVariable();
                String type = var.getPredef();
                String name = var.getName();
                String params = var.getParams();
                Element var_element = doc.createElement("var");
                var_element.setAttribute("name", name);
                var_element.setAttribute("type", type);
                if (params != null) {
                    var_element.setAttribute("params", params);
                }
                def_element.appendChild(var_element);
            }
            // append nodes
            for (BNode node : bn.getNodes()) {
                Element node_element = doc.createElement("node");
                rootElement.appendChild(node_element);
                Variable var = node.getVariable();
                node_element.setAttribute("var", var.getName());
                node_element.setAttribute("type", node.getType());
                if (!node.isTrainable()) {
                	node_element.setAttribute("trainable", String.valueOf(node.isTrainable()));
                }
                if (!node.isRoot()) {
                    for (Variable parent_var : node.getParents()) {
                        Element par_element = doc.createElement("parent");
                        node_element.appendChild(par_element);
                        par_element.setAttribute("var", parent_var.getName());
                    }
                }
                Element param_element = doc.createElement("params");
                String dump = node.getStateAsText();
                if (dump != null) {
                    param_element.setTextContent(dump);
                    node_element.appendChild(param_element);
                }
            }
            //Add tag sections
            Set<String> tags = bn.getTagNames();
            for (String tag : tags){
                Element tag_element = doc.createElement("tag");
                rootElement.appendChild(tag_element);
                tag_element.setAttribute("label", tag);
                Element variable_element = doc.createElement("variables");
                String nameStr = "\n";
                for (BNode node : bn.getTagged(tag)){
                    Variable var = node.getVariable();
                    String name = var.getName();
                    nameStr += name;
                    nameStr += "\n";
                }
                variable_element.setTextContent(nameStr);
                tag_element.appendChild(variable_element);
            }
            //Add tie sections
            for (BNode node : bn.getNodes()){
                try{
                    TiedNode tnode = (TiedNode) node;
                    if (tnode.getMaster() != null){
                        Element tie_element = doc.createElement("tie");
                        rootElement.appendChild(tie_element);
                        tie_element.setAttribute("source", tnode.getMaster().getName());
                        tie_element.setAttribute("dependant", node.getName());
                    }
                } catch (ClassCastException exception){
                    ;
                }
            }
            // for output to file
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            //for pretty print
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            DOMSource source = new DOMSource(doc);
            //write to file
            StreamResult file = new StreamResult(new File(filename));
            //write data
            transformer.transform(source, file);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    public static void main(String[] argv) {
        BNet bn = BNBuf.load("data/bn_simple.xml");

        BNode j = bn.getNode("John calls");
        BNode m = bn.getNode("Mary calls");
        BNode b = bn.getNode("Burglary");

        j.setInstance(true);
        m.setInstance(true);

        CGVarElim ve = new CGVarElim();
        ve.instantiate(bn);
        JPT jpt = ve.infer(b).getJPT();
        jpt.display();

        //IOBuf.save(bn, "data/bn_simple2.xml");
    }
}
