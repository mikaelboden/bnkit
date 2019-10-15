package reconstruction;

import api.PartialOrderGraph;
import bn.prob.EnumDistrib;
import dat.EnumSeq;
import dat.Enumerable;
import dat.POGraph;
import dat.file.AlnWriter;
import dat.file.FastaWriter;
import dat.file.TSVFile;
import vis.POAGJson;

import javax.swing.*;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.util.*;

/**
 * Command line version of GRASP.
 * @author mikael
 * @author ariane
 */
public class GraspCmd {

    public static void usage() {
        usage(0, null);
    }
    public static void usage(int error, String msg) {
        PrintStream out = System.out;
        if (error != 0)
            out = System.err;
        if (msg != null)
            out.println(msg + " (Error " + error + ")");
        out.println("Usage: GraspCmd \n" +
                "\t[-aln <alignment-file> -nwk <tree-file> -out <output-file>]\n" +
                "\t{-model <JTT(default)|Dayhoff|LG|WAG>}\n" +
                "\t{-thr <n-threads>}\n" +
                "\t{-joint (default) | -marg <branchpoint-id>} \n" +
                "\t{-gap}\n" +
                "\t{-savetree <tree-file>}\n" +
                "\t{-format <FASTA(default)|CLUSTAL|DISTRIB>}\n" +
                "\t{-help}");
        out.println("where \n" +
                "\talignment-file is a multiple-sequence alignment on FASTA or CLUSTAL format\n" +
                "\ttree-file is a phylogenetic tree on Newick format\n" +
                "\toutput-file will be populated by inferred ancestor or ancestors\n" +
                "\tInference is either joint (default) or marginal (marginal requires a branch-point to be nominated)\n" +
                "\t\"-gap\" means that the gap-character is included in the resulting output (default for CLUSTAL format)\n" +
                "\t\"-savetree\" re-saves the tree on Newick with ancestor names\n" +
                "\tThe output file is written on the specified format.");
        out.println("Notes: \n" +
                "\tGreater number of threads may improve processing time, but implies greater memory requirement (default is 1)." +
                "\tEvolutionary models include Jones-Taylor-Thornton (default), Dayhoff-Schwartz-Orcutt, Le-Gasquel and Whelan-Goldman");
        System.exit(error);
    }

    public static void main(String[] args) {

        String ALIGNMENT = null;
        String NEWICK = null;
        String OUTPUT = null;

        String[] MODELS = new String[] {"JTT", "Dayhoff", "LG", "WAG"};
        int MODEL_IDX = 0; // default model is that above indexed 0

        boolean GAPPY = false;
        String[] FORMATS = new String[] {"FASTA", "DISTRIB", "CLUSTAL"};
        int FORMAT_IDX = 0;

        int NTHREADS = 1;
        boolean INF_JOINT = true;
        String MARG_NODE = null;
        String SAVE_TREE = null;

        for (int a = 0; a < args.length; a ++) {
            if (args[a].startsWith("-")) {
                String arg = args[a].substring(1);
                if (arg.equalsIgnoreCase("aln") && args.length > a + 1) {
                    ALIGNMENT = args[++ a];
                } else if (arg.equalsIgnoreCase("nwk") && args.length > a + 1) {
                    NEWICK = args[++ a];
                } else if (arg.equalsIgnoreCase("out") && args.length > a + 1) {
                    OUTPUT = args[++a];
                } else if (arg.equalsIgnoreCase("joint")) {
                    INF_JOINT = true;
                } else if (arg.equalsIgnoreCase("gap")) {
                    GAPPY = true;
                } else if (arg.equalsIgnoreCase("savetree") && args.length > a + 1) {
                    SAVE_TREE = args[++a];
                } else if (arg.equalsIgnoreCase("marg") && args.length > a + 1) {
                    INF_JOINT = false;
                    MARG_NODE = args[++a];
                } else if (arg.equalsIgnoreCase("model") && args.length > a + 1) {
                    boolean found_model = false;
                    for (int i = 0; i < MODELS.length; i++) {
                        if (args[a + 1].equalsIgnoreCase(MODELS[i])) {
                            MODEL_IDX = i;
                            found_model = true;
                        }
                    }
                    if (!found_model)
                        usage(1, args[a + 1] + " is not a valid model name");
                } else if (arg.equalsIgnoreCase("format") && args.length > a + 1) {
                    boolean found_format = false;
                    for (int i = 0; i < FORMATS.length; i++) {
                        if (args[a + 1].equalsIgnoreCase(FORMATS[i])) {
                            FORMAT_IDX = i;
                            found_format = true;
                        }
                    }
                    if (!found_format)
                        usage(1, args[a + 1] + " is not a valid format name");
                } else if (arg.equalsIgnoreCase("thr") && args.length > a + 1) {
                    try {
                        NTHREADS = Integer.parseInt(args[++a]);
                    } catch (NumberFormatException e) {
                        System.err.println("Failed to set n-threads: " + args[a] + " is not a valid integer");
                    }
                } else if (arg.equalsIgnoreCase("help")) {
                    usage();
                }
            }
        }

        if (FORMATS[FORMAT_IDX].equalsIgnoreCase("CLUSTAL")) // Clustal files can only be "gappy"
            GAPPY = true;

        if (ALIGNMENT != null && NEWICK != null && OUTPUT != null) {
            try {
                // Load alignment, tree and run inference with specified settings across given number of threads
                System.out.println("Loading alignment, tree and starting inference (" + NTHREADS + " threads)");
                ASRPOG asr = null;
                if (INF_JOINT)
                    asr = new ASRPOG(ALIGNMENT, NEWICK, INF_JOINT, false, MODELS[MODEL_IDX], NTHREADS);
                else if (MARG_NODE != null) {
                    asr = new ASRPOG(ALIGNMENT, NEWICK, MARG_NODE, false, MODELS[MODEL_IDX], NTHREADS);
                    FORMAT_IDX = 1;
                } else {
                    usage(7, "Marginal inference requires the specification of a valid branch-ID");
                }
                System.out.println("Inference done; now assembling ancestor POGs");
                asr.performAssembly(NTHREADS);
                System.out.println("Assembling ancestor POGs done; now extracting most supported ancestor sequences (" + NTHREADS + " threads)");
                EnumSeq[] ancseqs = new EnumSeq[asr.getAncestralSeqLabels().size()];
                EnumDistrib[] ancdist = null;
                int[] ancidxs = null;
                int i = 0;
                for (String anclabel : asr.getAncestralSeqLabels()) { // iterate through all ancestors
                    PartialOrderGraph ancestor = asr.getGraph(anclabel);
                    ancseqs[i] = ancestor.getMostSupported(GAPPY);
                    if (anclabel.equals(MARG_NODE)) {
                        ancdist = ancestor.getDistribMostSupported(GAPPY);
                        ancidxs = ancestor.getIndicesMostSupported(GAPPY);
                    }
                    ancseqs[i].setName(anclabel);
                    i ++;
                }
                switch (FORMAT_IDX) {
                    case 0: // FASTA
                        FastaWriter fw = new FastaWriter(OUTPUT);
                        fw.save(ancseqs);
                        fw.close();
                        break;
                    case 1: // DISTRIB
                        if (ancdist != null) {
                            Object[][] m = new Object[ancdist.length + 1][];
                            for (int j = 0; j < ancdist.length; j++) {
                                if (ancdist[j] != null) {
                                    m[j + 1] = new Object[ancdist[j].getDomain().size() + 1];
                                    m[j + 1][0] = ancidxs[j] + 1;
                                    if (m[0] == null) {
                                        m[0] = new Object[ancdist[j].getDomain().size() + 1];
                                        m[0][0] = "Index";
                                    }
                                    for (int jj = 0; jj < m[j + 1].length - 1; jj++) {
                                        m[j + 1][jj + 1] = ancdist[j].get(jj);
                                        if (m[0][jj + 1] == null)
                                            m[0][jj + 1] = ancdist[0].getDomain().get(jj);
                                    }
                                }
                            }
                            for (int j = 0; j < ancdist.length; j++) {
                                if (ancdist[j] == null) {
                                    m[j + 1] = new Object[m[0].length];
                                    m[j + 1][0] = ancidxs[j] + 1;
                                    for (int jj = 0; jj < m[j + 1].length - 1; jj++)
                                        m[j + 1][jj + 1] = null;
                                }
                            }
                            TSVFile.saveObjects(OUTPUT, m);
                        } else
                            usage(8, "Invalid ancestor node label: " + MARG_NODE);
                        break;
                    case 2: // CLUSTAL
                        AlnWriter aw = new AlnWriter(OUTPUT);
                        aw.save(ancseqs);
                        aw.close();
                        break;
                }
                if (SAVE_TREE != null)
                    asr.saveTree(SAVE_TREE);

                System.out.println("Done.");
            } catch (IOException e) {
                usage(2, "Failed to read or write files: " + e.getMessage());
            } catch (InterruptedException e) {
                usage(6, "Process interrupted: " + e.getMessage());
            }
        } else {
            if (ALIGNMENT == null)
                usage(3, "Need to specify alignment (Clustal or FASTA file)");
            if (NEWICK == null)
                usage(4, "Need to specify phylogenetic tree (Newick file)");
            if (OUTPUT == null)
                usage(5, "Need to specify output file");
        }
    }
}
