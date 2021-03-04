package dat.pog;

import java.util.*;

/**
 * Indexed graph data structure.
 * Based on "adjacency matrix" but uses efficient representation using BitSet.
 * Beware: The plan is to keep this class thread-safe.
 */
public class IdxEdgeGraph<E extends Edge> extends IdxGraph {
    private Map<Integer, E> edges = null;

    /**
     * Create graph based on indexed nodes, edges represented by pairs of indices. Node and edge data can be attached.
     * The data structure allows for multiple "entry" and "exit" nodes, as would be required in some graphs with node order (partial order graphs for instance).
     * @param nNodes maximum number of nodes excluding (optional) virtual nodes to have multiple entry and exit points in the graph
     * @param undirected if true, edges are "undirected", else "directed"
     * @param terminated if true, a virtual start and a virtual end node are created to enable edges to mark multiple entry and exit nodes
     */
    public IdxEdgeGraph(int nNodes, boolean undirected, boolean terminated) {
        super(nNodes, undirected, terminated);
        this.edges = new HashMap<>();
    }

    /**
     * Retrieve the edge instance for a pair of node indices
     * @param from source node index (use -1 for terminal start edge)
     * @param to target node index (use N for a terminal end edge, where N is the number of possible/valid nodes)
     * @return edge instance if exists, else null
     * @throws InvalidIndexRuntimeException if either node index is invalid
     */
    public E getEdge(int from, int to) {
        if (isNode(from) && isNode(to)) {
            return this.edges.get(getEdgeIndex(from, to));
        } else if (from == -1 && isNode(to) && isTerminated()) {
            return this.edges.get(getEdgeIndex(from, to));
        } else if (isNode(from) && to == this.maxsize() && isTerminated()) {
            return this.edges.get(getEdgeIndex(from, to));
        } else {
            throw new InvalidIndexRuntimeException("Cannot retrieve edge between non-existent node/s: " + from + " or " + to);
        }
    }

    /**
     * Retrieve a collection of all the edges with no ref to index; these include disabled edges
     * @return all edges
     */
    public Collection<E> getEdges() {
        return edges.values();
    }

    /**
     * Modify the graph by adding an instance of an edge between two existing nodes.
     * If the graph is terminated, an edge can be added from a virtual start, or to a virtual end node.
     * @param from the source node index of the edge, -1 for a terminal start edge
     * @param to the destination node index of the edge, N for a terminal end edge, where N is the number of possible/valid nodes
     * @throws InvalidIndexRuntimeException if either node index is invalid
     */
    public synchronized void removeEdge(int from, int to) {
        if (isEdge(from, to)) {
            super.removeEdge(from, to);
            this.edges.remove(getEdgeIndex(from, to));
        }
    }

    /**
     * Modify the graph by disabling an edge between two existing nodes.
     * If there is an edge, it will be stashed to be enabled at a later stage.
     *
     * @param from the source node index of the edge, -1 for a terminal start edge
     * @param to the destination node index of the edge, N for a terminal end edge, where N is the number of possible/valid nodes
     * @return true if an edge exists, and then is disabled, false if no edge could be found
     * @throws InvalidIndexRuntimeException if either node index is invalid
     */
    public synchronized boolean disableEdge(int from, int to) {
        if (isEdge(from, to)) {
            super.removeEdge(from, to);
            return true;
        }
        return false;
    }

    /**
     * Modify the graph by adding an instance of an edge between two existing nodes.
     * If the edge already exists between the same node indices, it will be replaced.
     * If the graph is terminated, an edge can be added from a virtual start, or to a virtual end node.
     * @param from the source node index of the edge, -1 for a terminal start edge
     * @param to the destination node index of the edge, N for a terminal end edge, where N is the number of possible/valid nodes
     * @param edge the instance of the edge (optional)
     * @throws InvalidIndexRuntimeException if either node index is invalid
     */
    public synchronized boolean addEdge(int from, int to, E edge) {
        if (addEdge(from, to)) {
            this.edges.put(getEdgeIndex(from, to), edge);
            return true;
        } else
            return false;
    }

    /**
     * Modify the graph by enabling an instance of an edge between two existing nodes.
     * Requires that the edge already exists between the same node indices.
     * If the graph is terminated, an edge can be added from a virtual start, or to a virtual end node.
     * @param from the source node index of the edge, -1 for a terminal start edge
     * @param to the destination node index of the edge, N for a terminal end edge, where N is the number of possible/valid nodes
     * @return true if the edge instance was already there, so enabling could be completed, false if no edge instance was available
     * @throws InvalidIndexRuntimeException if either node index is invalid
     */
    public synchronized boolean enableEdge(int from, int to) {
        if (edges.get(getEdgeIndex(from, to)) != null) {
            super.addEdge(from, to);
            return true;
        } else
            return false;
    }

    public synchronized boolean addTerminalEdge(int from, E edge) {
        return addEdge(from, maxsize(), edge);
    }

    /**
     * Generate a text string that describes the graph, following the DOT format.
     * https://www.graphviz.org/pdf/dotguide.pdf
     *
     *   node  [style="rounded,filled,bold", shape=box, fixedsize=true, width=1.3, fontname="Arial"];
     *   edge  [style=bold, fontname="Arial", weight=100]
     * @return
     */
    public String toDOT() {
        StringBuffer buf = new StringBuffer();
        if (!isDirected())
            buf.append("graph " + getName() + " {\nnode [" + nodeDOT + "];\n");
        else
            buf.append("digraph " + getName() + " {\nrankdir=\"LR\";\nnode [" + nodeDOT + "];\n");
        for (int i = 0; i < nodes.length; i ++) {
            Node n = nodes[i];
            if (n != null) {
//                if (n.getLabel() == null) {
//                    n.setLabel(Integer.toString(i));
//                    buf.append(Integer.toString(i) + " [" + n.toDOT() + "];\n");
//                    n.setLabel(null);
//                } else
                    buf.append(Integer.toString(i) + " [" + n.toDOT() + "];\n");
            }
        }
        if (isTerminated()) {
            buf.append("_start [label=\"S(" + getName() + ")\",style=bold,fontcolor=red,fillcolor=gray,penwidth=0];\n");
            buf.append("_end [label=\"E(" + getName() +")\",style=bold,fontcolor=red,fillcolor=gray,penwidth=0];\n");
            buf.append("{rank=source;_start;}\n{rank=sink;_end;}\n");
        }
        buf.append("edge [" + edgeDOT + "];\n");
        if (isTerminated() && isDirected()) {
            for (int i = 0; i < startNodes.length(); i++) {
                if (startNodes.get(i)) {
                    E edge = getEdge(-1, i);
                    buf.append("_start -> " + i + (edge == null ? "\n" : "[" + edge.toDOT() + "]\n"));
                }
            }
        }
        for (int from = 0; from < edgesForward.length; from ++) {
            if (isNode(from)) {
                for (int to = isDirected() ? 0 : from; to < edgesForward[from].length(); to++) {
                    if (edgesForward[from].get(to)) {
                        E edge = getEdge(from, to);
                        if (!isDirected())
                            buf.append(from + " -- " + to + (edge == null ? "\n" : "[" + edge.toDOT() + "]\n"));
                        else
                            buf.append(from + " -> " + to + (edge == null ? "\n" : "[" + edge.toDOT() + "]\n"));
                    }
                }
            }
        }
        if (isTerminated() && isDirected()) {
            for (int i = 0; i < endNodes.length(); i++) {
                if (endNodes.get(i)) {
                    E edge = getEdge(i, nodes.length);
                    buf.append(i + " -> _end" + (edge == null ? "\n" : "[" + edge.toDOT() + "]\n"));
                }
            }
        }
        buf.append("}\n");
        return buf.toString();
    }


}

/**
 *
 */
class InvalidIndexRuntimeException extends RuntimeException {
    public InvalidIndexRuntimeException(String errmsg) {
        super(errmsg);
    }
}
