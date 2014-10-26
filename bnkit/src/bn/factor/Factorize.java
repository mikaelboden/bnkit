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
package bn.factor;

import dat.EnumVariable;
import bn.prob.GaussianDistrib;
import bn.JDF;
import bn.Predef;
import dat.Variable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * This class is intended to coordinate operations on factors.
 * It contains routines for main types of operations, e.g. product and marginalisation.
 * It also contains utility routines for transforming lists of variables etc.
 * Central coordination of factor operations should enable multi-threading of processes, 
 * e.g. factor products organised into binary trees.
 * @author mikael
 */
public class Factorize {
    protected static boolean VERBOSE = false;
    /** Calculate products linearly, don't consider order */
    protected static final int POOL_OPTION_LINEAR = 0;
    /** Calculate products according to binary tree, optimised to minimise computational cost */
    protected static final int POOL_OPTION_TREE = 1;
    protected static int PRODUCT_OPTION = -1; // choose strategy for complex cases by timing ("-1") or by fixed option (currently "0" and "1")

    /**
     * Empty constructor.
     */
    public Factorize() {
    }

    /**
     * Determine the difference between two arrays.
     * @param A the primary array
     * @param B the secondary array
     * @return the array which contain all elements of A except those in B
     */
    public static Variable[] getDifference(Variable[] A, Variable[] B) {
        List<Variable> accept = new ArrayList<>();
        for (int i = 0; i < A.length; i++) {
            boolean present_in_B = false;
            for (int j = 0; j < B.length; j++) {
                if (A[i].equals(B[j])) {
                    present_in_B = true;
                    break;
                }
            }
            if (!present_in_B) {
                accept.add(A[i]);
            }
        }
        Variable[] C = new Variable[accept.size()];
        accept.toArray(C);
        return C;
    }

    /**
     * Construct a non-redundant array of variables 
     * that contains all of the unique variables in the provided array.
     * @param A potentially unsorted array of variables, potentially with duplicates
     * @return array of the same variables, but with one copy of each unique variable
     */
    protected static Variable[] getNonredundant(Variable[] A) {
        List<Variable> accept = new ArrayList<>();
        for (int i = 0; i < A.length; i++) {
            if (!accept.contains(A[i])) {
                accept.add(A[i]);
            }
        }
        Variable[] B = new Variable[accept.size()];
        accept.toArray(B);
        return B;
    }

    /**
     * Construct an array of variables sorted according to their canonical index,
     * that contains all of the unique variables in the provided array.
     * @param A potentially unsorted array of variables, potentially with duplicates
     * @return array of the same variables, but sorted and unique
     */
    protected static Variable[] getNonredundantSorted(Variable[] A) {
        if (A.length < 2) {
            return A;
        }
        int j = 0;
        int i = 1;
        try {
            Arrays.sort(A);
        } catch (NullPointerException e) {
            System.out.println();
        }
        while (i < A.length) {
            if (A[i] == A[j]) {
                i++;
            } else {
                j++;
                A[j] = A[i];
                i++;
            }
        }
        return Arrays.copyOf(A, j + 1);
    }

    public static EnumVariable[] getEnumVars(Variable[] A) {
        int cnt_evars = 0;
        for (Variable var : A) {
            try {
                EnumVariable evar = (EnumVariable) var;
                cnt_evars++;
            } catch (ClassCastException e) {
            }
        }
        EnumVariable[] evars = new EnumVariable[cnt_evars];
        if (cnt_evars == 0) {
            return evars;
        } else {
            cnt_evars = 0;
            for (Variable var : A) {
                try {
                    EnumVariable evar = (EnumVariable) var;
                    evars[cnt_evars++] = evar;
                } catch (ClassCastException e) {
                }
            }
        }
        return evars;
    }
        
    /**
     * Gauges the alignment of enumerable variables in two tables to enable
     * optimization of products, in particular.
     * Assumes that variables are ordered per their canonical index.
     * @param Xvars enumerable variables of a factor X
     * @param Yvars enumerable variables of a factor Y
     * @return number of enumerable variables that overlap
     */
    public static int getOverlap(EnumVariable[] Xvars, EnumVariable[] Yvars) {
        if (Xvars == null) {
            Xvars = new EnumVariable[0];
        }
        if (Yvars == null) {
            Yvars = new EnumVariable[0];
        }
        int x = 0;
        int y = 0;
        int overlap = 0;
        while (Xvars.length > x && Yvars.length > y) {
            if (Xvars[x] == Yvars[y]) {
                x++;
                y++;
                overlap++;
            } else if (Xvars[x].getCanonicalIndex() < Yvars[y].getCanonicalIndex()) {
                x++;
            } else {
                y++;
            }
        }
        return overlap;
    }

    /**
     * Gauges the alignment of enumerable variables in two tables to enable
     * optimization of products, in particular.
     * Assumes that variables are ordered per their canonical index.
     * @param X factor X
     * @param Y factor Y
     * @return number of enumerable variables that overlap
     */
    public static int getOverlap(AbstractFactor X, AbstractFactor Y) {
        int x = 0;
        int y = 0;
        int overlap = 0;
        while (X.nEVars > x && Y.nEVars > y) {
            if (X.evars[x] == Y.evars[y]) {
                x++;
                y++;
                overlap++;
            } else if (X.evars[x].getCanonicalIndex() < Y.evars[y].getCanonicalIndex()) {
                x++;
            } else {
                y++;
            }
        }
        return overlap;
    }

    /**
     * Gauges the computational cost of multiplying the two tables, to enable
     * optimization of the order in which to products.
     * This "gauge" does not work well at the moment for structuring a binary tree.
     *
     * Assumes that variables are ordered per their canonical index.
     * Note that the cost of joining shared variables is optional.
     *
     * @param Xvars enumerable variables of a factor X
     * @param Yvars enumerable variables of a factor Y
     * @param includeJoin set to true if operations that involved share variables should be counted, false otherwise
     * @return complexity of the product X * Y
     */
    protected static int getComplexity(EnumVariable[] Xvars, EnumVariable[] Yvars, boolean includeJoin) {
        if (Xvars == null) {
            Xvars = new EnumVariable[0];
        }
        if (Yvars == null) {
            Yvars = new EnumVariable[0];
        }
        int x = 0;
        int y = 0;
        int multiplier = 1;
        while (Xvars.length > x && Yvars.length > y) {
            if (Xvars[x] == Yvars[y]) {
                if (includeJoin) {
                    multiplier *= Xvars[x].size();
                }
                x++;
                y++;
            } else if (Xvars[x].getCanonicalIndex() < Yvars[y].getCanonicalIndex()) {
                multiplier *= Xvars[x].size();
                x++;
            } else {
                multiplier *= Yvars[y].size();
                y++;
            }
        }
        while (Xvars.length > x) {
            multiplier *= Xvars[x++].size();
        }
        while (Yvars.length > y) {
            multiplier *= Yvars[y++].size();
        }
        return multiplier;
    }

    /**
     * Gauges the computational cost of multiplying the two tables, to enable
     * optimization of the order in which to products.
     * Assumes that variables are ordered per their canonical index.
     * Note that the cost of joining shared variables is optional.
     *
     * @param X factor X
     * @param Y factor Y
     * @param includeJoin set to true if operations that involved share variables should be counted, false otherwise
     * @return complexity of the product X * Y
     */
    public static int getComplexity(AbstractFactor X, AbstractFactor Y, boolean includeJoin) {
        return getComplexity(X.getEnumVars(), Y.getEnumVars(), includeJoin);
    }

    /**
     * Create a binary tree with all the pairwise products that are required to complete the full product.
     * The order of the products are determined so to minimise the computational cost over the full product.
     * @param factors all the factors that are to be multiplied
     * @return a binary tree that defines a good order
     */
    protected static FactorProductTree getProductTree(AbstractFactor[] factors) {
        int N = factors.length;
        // deal with special cases
        if (N == 0) 
            return null;
        if (N == 1) 
            return new FactorProductTree(factors[0]);
        if (N == 2) 
            return new FactorProductTree(new FactorProductTree(factors[0]), new FactorProductTree(factors[1]));
        // more than two so optimisation is performed...
        // construct the initial working set, of:
        // nodes that MUST be used in the final tree
        List<FactorProductTree> fpool = new ArrayList<>();
        // first leaves for all factors that must take part of the tree
        for (int i = 0; i < N; i ++) {
            fpool.add(new FactorProductTree(factors[i]));
        }
        // now, the main part of repeatedly identifying the best node and integrating that into the final tree
        // this will always be N - 1 products/internal nodes (where N is the number of factors)
        FactorProductTree node = null;
        for (int rank = 0; rank < N - 1; rank++) {
            int lowest = Integer.MAX_VALUE;
            int a = -1;
            int b = -1;
            for (int i = 0; i < fpool.size(); i++) {
                EnumVariable[] evars_i = fpool.get(i).getEnumVars();
                for (int j = i + 1; j < fpool.size(); j++) {
                    EnumVariable[] evars_j = fpool.get(j).getEnumVars();
                    Integer cost = getComplexity(evars_i, evars_j, true); // this is quick so no real need to cache these numbers
                    if (cost < lowest) {
                        a = i;
                        b = j;
                        lowest = cost;
                    }
                }
            }
            FactorProductTree child_a = fpool.get(a);
            FactorProductTree child_b = fpool.get(b);
            node = new FactorProductTree(child_a, child_b);
            fpool.remove(b); // have to remove in opposite order, to not change the indices where "a" is
            fpool.remove(a);
            fpool.add(node);
        }
        return node;
    }

    /**
     * Recursive function to perform a single product of factors, potentially resulting 
     * from products themselves.
     * @return the product of the factors represented by this node in a binary tree
     */
    protected static AbstractFactor getProduct(FactorProductTree node) {
        if (node.getFactor() != null) {
            return node.getFactor();
        }
        AbstractFactor X = getProduct(node.x);
        AbstractFactor Y = getProduct(node.y);
        AbstractFactor f = getProduct(X, Y);
        node.setFactor(f);
        return f;
    }

    /**
     * Perform products of factors, by organising them into a binary tree of single products,
     * which minimises the number of elements in the resulting, intermittent factors.
     * @param factors the factors that are multiplied
     * @return the product of the factors
     */
    public static AbstractFactor getProduct(AbstractFactor[] factors) {
        if (factors.length == 0) {
            return null;
        }
        FactorProductTree tree = getProductTree(factors);
        return getProduct(tree);
    }


    /**
     * Concatenate two arrays of variables into one.
     * Does not consider order or duplicates.
     * @param one
     * @param two
     * @return
     */
    protected static Variable[] getConcat(Variable[] one, Variable[] two) {
        if (one == null && two == null) {
            return new Variable[0];
        } else if (one == null) {
            Variable[] arr = new Variable[two.length];
            System.arraycopy(two, 0, arr, 0, two.length);
            return arr;
        } else if (two == null) {
            Variable[] arr = new Variable[one.length];
            System.arraycopy(one, 0, arr, 0, one.length);
            return arr;
        } else {
            Variable[] arr = new Variable[one.length + two.length];
            System.arraycopy(one, 0, arr, 0, one.length);
            System.arraycopy(two, 0, arr, one.length, two.length);
            return arr;
        }
    }

    /**
     * Create indices that allow quick cross-referencing between two lists of variables.
     * @param xvars variables in a table X
     * @param xcross indices to go from X to Y, i.e. at [x] you find y (or -1 if no mapping)
     * @param yvars variables in a table Y
     * @param ycross indices to go from Y to X, i.e. at [y] you find x (or -1 if no mapping)
     * @return number of positions that overlap
     */
    public static int getCrossref(Variable[] xvars, int[] xcross, Variable[] yvars, int[] ycross) {
        if (xcross != null && ycross != null) {
            if (xvars.length != xcross.length || yvars.length != ycross.length) {
                throw new RuntimeException("Lists must be equal");
            }
            int noverlap = 0; // number of overlapping variables
            for (int j = 0; j < yvars.length; j++) {
                ycross[j] = -1; // Assume Yj does not exist in X
            }
            for (int i = 0; i < xvars.length; i++) {
                xcross[i] = -1; // Assume Xi does not exist in Y
                for (int j = 0; j < yvars.length; j++) {
                    if (xvars[i].equals(yvars[j])) {
                        // this variable Xi is at Yj
                        xcross[i] = j;
                        ycross[j] = i;
                        noverlap++;
                        break;
                    }
                }
            }
            return noverlap;
        } else if (xcross != null) {
            // ycross == null
            if (xcross.length > 0) {
                if (xvars.length != xcross.length) {
                    throw new RuntimeException("Lists must be equal");
                }
                int noverlap = 0; // number of overlapping variables
                for (int i = 0; i < xvars.length; i++) {
                    xcross[i] = -1; // Assume Xi does not exist in Y
                    for (int j = 0; j < yvars.length; j++) {
                        if (xvars[i].equals(yvars[j])) {
                            // this variable Xi is at Yj
                            xcross[i] = j;
                            noverlap++;
                            break;
                        }
                    }
                }
                return noverlap;
            }
        } else if (ycross != null) {
            // xcross == null
            if (ycross.length > 0) {
                if (yvars.length != ycross.length) {
                    throw new RuntimeException("Y Lists must be equal");
                }
                int noverlap = 0; // number of overlapping variables
                for (int i = 0; i < yvars.length; i++) {
                    ycross[i] = -1; // Assume Yi does not exist in X
                    for (int j = 0; j < xvars.length; j++) {
                        if (yvars[i].equals(xvars[j])) {
                            // this variable Yi is at Xj
                            ycross[i] = j;
                            noverlap++;
                            break;
                        }
                    }
                }
                return noverlap;
            }
        }
        return 0;
    }

    /**
     * Construct a new table that is the result of a factor product of the two specified tables.
     * Works in the general case but there are significant efficiency gains if joint variables are ordered.
     * The implementation currently identifies different cases some of which can be run efficiently,
     * whilst others require tricky indexing. The method is thus quite long but most of the time only a fraction
     * of the code is actually executed.
     *
     * TODO: Make informed choices as to what implementation of AbstractFactor should be used.
     * Currently only DenseFactor is used.
     *
     * @param X one table
     * @param Y other table
     * @return the product of one and the other table
     */
    public static AbstractFactor getProduct(AbstractFactor X, AbstractFactor Y) {
        // System.err.println("Factor product " + X.toString() + " x " + Y.toString());
        // First resolve cases with tables without enumerable variables
        if (X.nEVars == 0 && Y.nEVars == 0) {
            // both tables lack enumerable variables
            AbstractFactor dt = new DenseFactor(getConcat(X.nvars, Y.nvars));
            if (X.isTraced() || Y.isTraced()) {
                dt.setTraced(true);
            }
            dt.setValue(X.getValue() * Y.getValue());
            if (X.isJDF() && Y.isJDF()) {
                dt.setJDF(JDF.combine(X.getJDF(), Y.getJDF()));
            } else if (X.isJDF()) {
                dt.setJDF(X.getJDF());
            } else if (Y.isJDF()) {
                dt.setJDF(Y.getJDF());
            }
            if (X.isTraced()) {
                dt.addAssign(X.getAssign());
            }
            if (Y.isTraced()) {
                dt.addAssign(Y.getAssign());
            }
            return dt;
        } else if (X.nEVars == 0) {
            // only X is without enumerables
            AbstractFactor dt = new DenseFactor(getConcat(Y.evars, getConcat(X.nvars, Y.nvars)));
            if (X.isTraced() || Y.isTraced()) {
                dt.setTraced(true);
            }
            for (int j = 0; j < Y.getSize(); j++) {
                dt.setValue(j, Y.getValue(j) * X.getValue());
                if (X.isJDF() && Y.isJDF()) {
                    dt.setJDF(j, JDF.combine(X.getJDF(), Y.getJDF(j)));
                } else if (X.isJDF()) {
                    dt.setJDF(j, X.getJDF());
                } else if (Y.isJDF()) {
                    dt.setJDF(j, Y.getJDF(j));
                }
                if (X.isTraced()) {
                    dt.addAssign(j, X.getAssign());
                }
                if (Y.isTraced()) {
                    dt.addAssign(j, Y.getAssign(j));
                }
            }
            return dt;
        } else if (Y.nEVars == 0) {
            // only Y is without enumerables
            AbstractFactor dt = new DenseFactor(getConcat(X.evars, getConcat(X.nvars, Y.nvars)));
            if (X.isTraced() || Y.isTraced()) {
                dt.setTraced(true);
            }
            for (int j = 0; j < X.getSize(); j++) {
                dt.setValue(j, X.getValue(j) * Y.getValue());
                if (X.isJDF() && Y.isJDF()) {
                    dt.setJDF(j, JDF.combine(X.getJDF(j), Y.getJDF()));
                } else if (X.isJDF()) {
                    dt.setJDF(j, X.getJDF(j));
                } else if (Y.isJDF()) {
                    dt.setJDF(j, Y.getJDF());
                }
                if (X.isTraced()) {
                    dt.addAssign(j, X.getAssign(j));
                }
                if (Y.isTraced()) {
                    dt.addAssign(j, Y.getAssign());
                }
            }
            return dt;
        }
        // Both tables have enumerable variables, so let's work out how they relate
        int[] xcross2y = new int[X.nEVars]; // map from X to Y indices [x] = y
        int[] ycross2x = new int[Y.nEVars]; // map from Y to X indices [y] = x
        int noverlap = 0; // number of overlapping variables
        noverlap = getCrossref(X.evars, xcross2y, Y.evars, ycross2x);
        if (VERBOSE) {
            System.err.println("#overlap = " + noverlap);
        }
        // check if ordered
        boolean ordered = true; // true if *all* overlapping variables are in the same order
        int prev = -1;
        for (int i = 0; i < xcross2y.length && ordered; i++) {
            if (xcross2y[i] != -1) {
                if (xcross2y[i] <= prev) {
                    ordered = false;
                    break;
                }
                prev = xcross2y[i];
            }
        }
        // Before handling complex scenarios, consider some special, simpler cases
        // 1. two tables with the same variables in the same order
        if (noverlap == Math.min(X.nEVars, Y.nEVars)) {
            // at least one table is "contained" by the other
            if (X.nEVars == Y.nEVars) {
                Object[] ykey = new Object[Y.nEVars];
                AbstractFactor dt = new DenseFactor(getConcat(X.evars, getConcat(X.nvars, Y.nvars)));
                if (X.isTraced() || Y.isTraced()) {
                    dt.setTraced(true);
                }
                for (int x = 0; x < X.getSize(); x++) {
                    int y = x; // if ordered the indices in the tables are identical
                    if (!ordered) {
                        // re-index since the variables are listed in a different order
                        Object[] xkey = X.getKey(x);
                        for (int i = 0; i < xkey.length; i++) {
                            ykey[xcross2y[i]] = xkey[i];
                        }
                        y = Y.getIndex(ykey);
                    }
                    dt.setValue(x, X.getValue(x) * Y.getValue(y));
                    if (X.isJDF() && Y.isJDF()) {
                        dt.setJDF(x, JDF.combine(X.getJDF(x), Y.getJDF(y)));
                    } else if (X.isJDF()) {
                        dt.setJDF(x, X.getJDF(x));
                    } else if (Y.isJDF()) {
                        dt.setJDF(x, Y.getJDF(y));
                    }
                    if (X.isTraced()) {
                        dt.addAssign(x, X.getAssign(x));
                    }
                    if (Y.isTraced()) {
                        dt.addAssign(x, Y.getAssign(y));
                    }
                }
                if (VERBOSE) {
                    System.err.println("DT: Complete overlap, ordered = " + ordered + " : " + X.toString() + " x " + Y.toString());
                }
                return dt;
            } else if (X.nEVars > Y.nEVars) {
                // Y is more compact than X
                // some variables in X are not in Y
                Set<EnumVariable> notInY = new HashSet<>();
                for (int i = 0; i < xcross2y.length; i++) {
                    if (xcross2y[i] == -1) {
                        notInY.add(X.evars[i]);
                    }
                }
                Object[] ykey = new Object[Y.nEVars];
                AbstractFactor dt = new DenseFactor(getConcat(X.evars, getConcat(X.nvars, Y.nvars)));
                if (X.isTraced() || Y.isTraced()) {
                    dt.setTraced(true);
                }
                for (int x = 0; x < X.getSize(); x++) {
                    double xval = X.getValue(x);
                    if (xval == 0) {
                        continue; // no point in continuing since product will always be zero for entries with this x-value
                    }
                    int y; // there can only be one index in Y (if it is contained in X)
                    if (!ordered) {
                        // re-index considering that variables are listed in a different order
                        Object[] xkey = X.getKey(x);
                        for (int i = 0; i < xkey.length; i++) {
                            if (xcross2y[i] != -1) {
                                ykey[xcross2y[i]] = xkey[i];
                            }
                        }
                        y = Y.getIndex(ykey);
                    } else {
                        // re-index but ordered, not sure if this is quicker than above... TODO: test
                        y = X.maskIndex(x, notInY);
                    }
                    double yval = Y.getValue(y);
                    if (yval == 0) {
                        continue; // the product will be zero no what
                    }
                    int idx = x;
                    dt.setValue(idx, xval * yval);
                    if (X.isJDF() && Y.isJDF()) {
                        dt.setJDF(idx, JDF.combine(X.getJDF(x), Y.getJDF(y)));
                    } else if (X.isJDF()) {
                        dt.setJDF(idx, X.getJDF(x));
                    } else if (Y.isJDF()) {
                        dt.setJDF(idx, Y.getJDF(y));
                    }
                    if (X.isTraced()) {
                        dt.addAssign(idx, X.getAssign(x));
                    }
                    if (Y.isTraced()) {
                        dt.addAssign(idx, Y.getAssign(y));
                    }
                }
                if (VERBOSE) {
                    System.err.println("DT: Partial overlap (X>Y), ordered = " + ordered + " : " + X.toString() + " x " + Y.toString());
                }
                return dt;
            } else if (Y.nEVars > X.nEVars) {
                // X is more compact than Y
                // some variables in Y are not in X
                Set<EnumVariable> notInX = new HashSet<>();
                for (int i = 0; i < ycross2x.length; i++) {
                    if (ycross2x[i] == -1) {
                        notInX.add(Y.evars[i]);
                    }
                }
                Object[] xkey = new Object[X.nEVars];
                AbstractFactor dt = new DenseFactor(getConcat(Y.evars, getConcat(X.nvars, Y.nvars)));
                if (X.isTraced() || Y.isTraced()) {
                    dt.setTraced(true);
                }
                for (int y = 0; y < Y.getSize(); y++) {
                    double yval = Y.getValue(y);
                    if (yval == 0) {
                        continue; // no point in continuing since product will always be zero for entries with this x-value
                    }
                    int x; // there can only be one index in X (if it is contained in Y)
                    if (!ordered) {
                        // re-index considering that variables are listed in a different order
                        Object[] ykey = Y.getKey(y);
                        for (int i = 0; i < ykey.length; i++) {
                            if (ycross2x[i] != -1) {
                                xkey[ycross2x[i]] = ykey[i];
                            }
                        }
                        x = X.getIndex(xkey);
                    } else {
                        // re-index but ordered, not sure if this is quicker than above... TODO: test
                        x = Y.maskIndex(y, notInX);
                    }
                    double xval = X.getValue(x);
                    if (xval == 0) {
                        continue; // the product will be zero no what
                    }
                    int idx = y;
                    dt.setValue(idx, xval * yval);
                    if (X.isJDF() && Y.isJDF()) {
                        dt.setJDF(idx, JDF.combine(X.getJDF(x), Y.getJDF(y)));
                    } else if (X.isJDF()) {
                        dt.setJDF(idx, X.getJDF(x));
                    } else if (Y.isJDF()) {
                        dt.setJDF(idx, Y.getJDF(y));
                    }
                    if (X.isTraced()) {
                        dt.addAssign(idx, X.getAssign(x));
                    }
                    if (Y.isTraced()) {
                        dt.addAssign(idx, Y.getAssign(y));
                    }
                }
                if (VERBOSE) {
                    System.err.println("DT: Partial overlap (X<Y), ordered = " + ordered + " : " + X.toString() + " x " + Y.toString());
                }
                return dt;
            }
        }
        // Failing the above, we must construct a table which is an amalgamate of the two.
        AbstractFactor dt = new DenseFactor(getConcat(getConcat(X.evars, Y.evars), getConcat(X.nvars, Y.nvars)));
        if (X.isTraced() || Y.isTraced()) {
            dt.setTraced(true);
        }
        Object[] reskey = new Object[dt.nEVars]; // this will initialise all elements to null
        int[] xcross2dt = new int[X.nEVars]; // map from X index to dt index
        int[] ycross2dt = new int[Y.nEVars]; // map from Y index to dt index
        getCrossref(X.evars, xcross2dt, dt.evars, null);
        getCrossref(Y.evars, ycross2dt, dt.evars, null);
        // 2. two tables have nothing in common
        if (noverlap == 0) {
            for (int x = 0; x < X.getSize(); x++) {
                double xval = X.getValue(x);
                if (xval == 0) {
                    continue; // no point in continuing since product will always be zero for entries with this x-value
                }
                Object[] xkey = X.getKey(x);
                for (int y = 0; y < Y.getSize(); y++) {
                    double yval = Y.getValue(y);
                    if (yval == 0) {
                        continue; // the product will be zero no what
                    }
                    Object[] ykey = Y.getKey(y);
                    for (int i = 0; i < xkey.length; i++) {
                        reskey[xcross2dt[i]] = xkey[i];
                    }
                    for (int i = 0; i < ykey.length; i++) {
                        reskey[ycross2dt[i]] = ykey[i];
                    }
                    int idx = dt.getIndex(reskey);
                    // **************************
                    //     This does NOT work??
                    // **************************
                    dt.setValue(idx, xval * yval);
                    if (X.isJDF() && Y.isJDF()) {
                        dt.setJDF(idx, JDF.combine(X.getJDF(x), Y.getJDF(y)));
                    } else if (X.isJDF()) {
                        dt.setJDF(idx, X.getJDF(x));
                    } else if (Y.isJDF()) {
                        dt.setJDF(idx, Y.getJDF(y));
                    }
                    if (X.isTraced()) {
                        dt.addAssign(idx, X.getAssign(x));
                    }
                    if (Y.isTraced()) {
                        dt.addAssign(idx, Y.getAssign(y));
                    }
                }
            }
            if (VERBOSE) {
                System.err.println("DT: No overlap : " + X.toString() + " x " + Y.toString());
            }
            return dt;
        }
        // 3. General case, if none of those implemented above has worked
        Object[] searchkey = new Object[Y.nEVars]; // this will initialise all elements to null
        int option = PRODUCT_OPTION;
        long start0 = 0;
        long start1 = 0;
        long total0 = 0;
        long total1 = 0;
        for (int x = 0; x < X.getSize(); x++) {
            double xval = X.getValue(x);
            if (xval == 0) {
                continue; // no point in continuing since product will always be zero for entries with this x-value
            }
            Object[] xkey = X.getKey(x);
            for (int i = 0; i < xcross2y.length; i++) {
                if (xcross2y[i] > - 1) {
                    searchkey[xcross2y[i]] = xkey[i];
                }
                reskey[xcross2dt[i]] = xkey[i];
            }
            if (start0 == 0 && option == -1) {
                start0 = System.nanoTime();
                option = 0;
            } else if (start1 == 0 && option == -1) {
                start1 = System.nanoTime();
                option = 1;
            }
            // before computing all *matching* indices in Y, weigh the cost of traversing all indices instead, to subsequently check for match
            if (option == 0) {
                int[] yindices = Y.getIndices(searchkey);
                for (int y : yindices) {
                    double yval = Y.getValue(y);
                    if (yval == 0) {
                        continue; // the product will be zero no what
                    }
                    Object[] ykey = Y.getKey(y);
                    for (int i = 0; i < ykey.length; i++) {
                        reskey[ycross2dt[i]] = ykey[i];
                    }
                    int idx = dt.getIndex(reskey);
                    dt.setValue(idx, xval * yval);
                    if (X.isJDF() && Y.isJDF()) {
                        dt.setJDF(idx, JDF.combine(X.getJDF(x), Y.getJDF(y)));
                    } else if (X.isJDF()) {
                        dt.setJDF(idx, X.getJDF(x));
                    } else if (Y.isJDF()) {
                        dt.setJDF(idx, Y.getJDF(y));
                    }
                    if (X.isTraced()) {
                        dt.addAssign(idx, X.getAssign(x));
                    }
                    if (Y.isTraced()) {
                        dt.addAssign(idx, Y.getAssign(y));
                    }
                }
                if (start0 != 0) {
                    total0 = System.nanoTime() - start0;
                    option = -1;
                }
            } else if (option == 1) {
                for (int y = 0; y < Y.getSize(); y++) {
                    if (Y.isMatch(searchkey, y)) {
                        double yval = Y.getValue(y);
                        if (yval == 0) {
                            continue; // the product will be zero no what
                        }
                        Object[] ykey = Y.getKey(y);
                        for (int i = 0; i < ykey.length; i++) {
                            reskey[ycross2dt[i]] = ykey[i];
                        }
                        int idx = dt.getIndex(reskey);
                        dt.setValue(idx, xval * yval);
                        if (X.isJDF() && Y.isJDF()) {
                            dt.setJDF(idx, JDF.combine(X.getJDF(x), Y.getJDF(y)));
                        } else if (X.isJDF()) {
                            dt.setJDF(idx, X.getJDF(x));
                        } else if (Y.isJDF()) {
                            dt.setJDF(idx, Y.getJDF(y));
                        }
                        if (X.isTraced()) {
                            dt.addAssign(idx, X.getAssign(x));
                        }
                        if (Y.isTraced()) {
                            dt.addAssign(idx, Y.getAssign(y));
                        }
                    }
                }
                if (start1 != 0) {
                    total1 = System.nanoTime() - start1;
                    option = -1;
                }
            }
            if (start1 != 0) {
                // done with timing
                if (total0 > total1) {
                    option = 1;
                } else {
                    option = 0;
                }
            }
        }
        if (VERBOSE) {
            System.err.println("DT: Generic case: " + X.toString() + " x " + Y.toString() + " Option = " + option + " (Option 0 took " + total0 + "ns. Option 1 took " + total1 + "ns.)");
        }
        return dt;
    }

    /**
     * Construct a new table from an existing, by summing-out specified variable/s.
     *
     * TODO: Make informed choices as to what implementation of AbstractFactor should be used.
     * Currently only DenseFactor is used.
     *
     * @param X existing table
     * @param anyvars variables to sum-out
     * @return the resulting "margin" of the table
     */
    public static AbstractFactor getMargin(AbstractFactor X, Variable... anyvars) {
        // sort and weed out duplicates
        Variable[] uniqueVars = getNonredundantSorted(anyvars);
        // first check that X has enumerable variables, because that would be required
        if (X.nEVars == 0) {
            return X;
        }
        // if X is ok, get rid of non-enumerables
        EnumVariable[] evars = getEnumVars(uniqueVars);
        // now, resolve the relationships between X's variables and those to be summed-out
        Variable[] yvars = getDifference(getConcat(X.evars, X.nvars), evars);
        // construct new table
        AbstractFactor Y = new DenseFactor(yvars);
        if (Y.getSize() == 1) {
            // we are creating a factor with no enumerable variables)
            double sum = 0;
            JDF first = null;
            JDF subsequent = null;
            JDF mixture = null;
            double prev_weight = 0;
            for (int x = 0; x < X.getSize(); x++) {
                double xval = X.getValue(x);
                sum += xval;
            }
            Y.setValue(sum);
            if (X.isJDF()) {
                for (int x = 0; x < X.getSize(); x++) {
                    double nxval = X.getValue(x) / sum; // normalized
                    if (nxval != 0) {
                        if (first == null) {
                            // this will be true only once, for the first entry that will be collapsed
                            first = X.getJDF(x); // save the JDF for later
                            prev_weight = nxval; // save the value associated with this entry to weight the distributions
                        } else if (mixture == null) {
                            // for the subsequent entry, the mixture is created, both JDF weighted
                            subsequent = X.getJDF(x);
                            if (subsequent != null) {
                                mixture = JDF.mix(first, prev_weight, subsequent, nxval);
                            }
                        } else {
                            // for all subsequent entries, the mixture is mixed unchanged with the new entry's JDF weighted
                            subsequent = X.getJDF(x);
                            if (subsequent != null) {
                                mixture = JDF.mix(mixture, subsequent, nxval);
                            }
                        }
                    }
                }
                if (first != null) {
                    if (mixture != null) {
                        Y.setJDF(mixture);
                    } else {
                        Y.setJDF(first);
                    }
                }
            }
        } else {
            // work out how variables in Y map to X so that we can search X from each index in Y
            int[] ycross2x = new int[Y.nEVars];
            getCrossref(Y.evars, ycross2x, X.evars, null);
            Object[] xkey_search = new Object[X.nEVars];
            for (int y = 0; y < Y.getSize(); y++) {
                // FIXME: loop's not efficient for sparse factors, start with X, be selective about Y?
                Object[] ykey = Y.getKey(y);
                for (int i = 0; i < ykey.length; i++) {
                    xkey_search[ycross2x[i]] = ykey[i];
                }
                double sum = 0;
                int[] indices = X.getIndices(xkey_search);
                JDF first = null;
                JDF subsequent = null;
                JDF mixture = null;
                double prev_weight = 0;
                for (int x : indices) {
                    double xval = X.getValue(x);
                    sum += xval;
                }
                Y.setValue(y, sum);
                if (X.isJDF()) {
                    for (int x : indices) {
                        double nxval = X.getValue(x) / sum; // normalized
                        if (nxval != 0) {
                            if (first == null) {
                                // this will be true only once, for the first entry that will be collapsed
                                first = X.getJDF(x); // save the JDF for later
                                prev_weight = nxval; // save the value associated with this entry to weight the distributions
                            } else if (mixture == null) {
                                // for the subsequent entry, the mixture is created, both JDF weighted
                                subsequent = X.getJDF(x);
                                if (subsequent != null) {
                                    mixture = JDF.mix(first, prev_weight, subsequent, nxval);
                                }
                            } else {
                                // for all subsequent entries, the mixture is mixed unchanged with the new entry's JDF weighted
                                subsequent = X.getJDF(x);
                                if (subsequent != null) {
                                    mixture = JDF.mix(mixture, subsequent, nxval);
                                }
                            }
                        }
                    }
                    if (first != null) {
                        if (mixture != null) {
                            Y.setJDF(y, mixture);
                        } else {
                            Y.setJDF(y, first);
                        }
                    }
                }
            }
        }
        return Y;
    }

    /**
     * Construct a new table from an existing, by maxing-out specified variable/s, and tracing
     * the assignment that provided the maximum value in the resulting table.
     * This code is based on that of getMargin.
     *
     * @param X existing table
     * @param anyvars variables to max-out
     * @return the resulting factor
     */
    public static AbstractFactor getMaxMargin(AbstractFactor X, Variable... anyvars) {
        // sort and weed out duplicates
        Variable[] uniqueVars = getNonredundantSorted(anyvars);
        // first check that X has enumerable variables, because that would be required
        if (X.nEVars == 0) {
            return X;
        }
        // if X is ok, get rid of non-enumerables
        EnumVariable[] evars = getEnumVars(uniqueVars);
        int[] ecross2x = new int[evars.length];
        getCrossref(evars, ecross2x, X.evars, null); // so we can map maxed-out vars to the original table
        // now, resolve the relationships between X's variables and those to be summed-out
        Variable[] yvars = getDifference(getConcat(X.evars, X.nvars), evars);
        // construct new table
        AbstractFactor Y = new DenseFactor(yvars);
        Y.setTraced(true); // make sure we save assignments made in max-outs
        if (!Y.hasEnumVars()) {
            double max = Double.NEGATIVE_INFINITY;
            int maxidx = 0;
            for (int x = 0; x < X.getSize(); x++) {
                double xval = X.getValue(x);
                if (xval > max) {
                    max = xval;
                    maxidx = x;
                }
            }
            Y.setValue(max);
            if (Y.isJDF()) {
                Y.setJDF(X.getJDF(maxidx));
            }
            // transfer old assignments
            if (X.isTraced())
                Y.addAssign(X.getAssign(maxidx));
            // Trace implied assignments
            Object[] xkey = X.getKey(maxidx);
            for (int i = 0; i < evars.length; i++) {
                Variable.Assignment a = new Variable.Assignment(evars[i], xkey[ecross2x[i]]);
                Y.addAssign(a);
            }
        } else {
            // work out how variables in Y map to X so that we can search X from each index in Y
            int[] ycross2x = new int[Y.nEVars];
            int[] xcross2y = new int[X.nEVars];
            getCrossref(Y.evars, ycross2x, X.evars, xcross2y);
            Object[] xkey_search = new Object[X.nEVars];
            for (int y = 0; y < Y.getSize(); y++) {
                // FIXME: loop's not efficient for sparse factors
                Object[] ykey = Y.getKey(y);
                for (int i = 0; i < ykey.length; i++) {
                    xkey_search[ycross2x[i]] = ykey[i];
                }
                double max = Double.NEGATIVE_INFINITY;
                int maxidx = 0;
                int[] indices = X.getIndices(xkey_search);
                for (int x : indices) {
                    double xval = X.getValue(x);
                    if (xval > max) {
                        max = xval;
                        maxidx = x;
                    }
                }
                Y.setValue(y, max);
                if (Y.isJDF()) {
                    Y.setJDF(y, X.getJDF(maxidx));
                }
                // transfer old assignments
                if (X.isTraced())
                    Y.addAssign(y, X.getAssign(maxidx));
                // Trace implied assignments
                Object[] xkey = X.getKey(maxidx);
                for (int i = 0; i < evars.length; i++) {
                    Variable.Assignment a = new Variable.Assignment(evars[i], xkey[ecross2x[i]]);
                    Y.addAssign(y, a);
                }
            }
        }
        return Y;
    }

    /**
     * Determine a normalised version of the factor
     * @param X factor
     * @return a normalised copy of the provided factor
     */
    public static AbstractFactor getNormal(AbstractFactor X) {
        AbstractFactor Y = new DenseFactor(getConcat(X.evars, X.nvars));
        if (X.hasEnumVars()) {
            double sum = X.getSum();
            for (int i = 0; i < X.getSize(); i++) {
                Y.setValue(i, X.getValue(i) / sum);
                if (X.isJDF()) {
                    Y.setJDF(i, X.getJDF(i));
                }
            }
        } else {
            Y.setValue(X.getValue());
            if (X.isJDF()) {
                Y.setJDF(X.getJDF());
            }
        }
        return Y;
    }

    /*************************** Methods to test AbstractFactor **************************/
    
    protected static Variable[] getVariablePool(long seed, int n) {
        Random random = new Random(seed);
        Variable[] vars = new Variable[n];
        for (int i = 0; i < vars.length; i++) {
            int type = random.nextInt(5);
            Variable var = null;
            switch (type) {
                case 0:
                    var = Predef.Boolean();
                    break;
                case 1:
                    var = Predef.Nominal("a", "b", "c");
                    break;
                case 2:
                    var = Predef.NucleicAcid();
                    break;
                case 3:
                    var = Predef.Real();
                    break;
                case 4:
                    var = Predef.Number(random.nextInt(8) + 2);
                    break;
            }
            vars[i] = var;
        }
        return vars;
    }

    protected static Variable[] getSubset(long seed, Variable[] vars, int n) {
        Random random = new Random(seed);
        Set<Variable> unique = new HashSet<>();
        n = Math.min(vars.length, n);
        Variable[] subset = new Variable[n];
        while (unique.size() < n) {
            unique.add(vars[random.nextInt(vars.length)]);
        }
        unique.toArray(subset);
        return subset;
    }

    protected static AbstractFactor[] getFactorPool(long seed, Variable[] vars, int n) {
        Random random = new Random(seed);
        int M = Math.abs((int) (random.nextGaussian() * n) + 1);
        int N = Math.abs((int) (random.nextGaussian() * n) + 1);
        AbstractFactor[] dfs = new DenseFactor[N];
        for (int i = 0; i < dfs.length; i++) {
            int nvars = random.nextInt(Math.max(1, M));
            if (nvars > 0) {
                Variable[] myvars = getSubset(random.nextInt(), vars, nvars);
                dfs[i] = new DenseFactor(myvars);
            } else {
                dfs[i] = new DenseFactor();
            }
            int npop = dfs[i].getSize(); //random.nextInt(dfs[i].getSize()); // number of entries to populate
            for (int j = 0; j < npop; j++) {
                if (dfs[i].getSize() == 1) {
                    dfs[i].setValue(Math.abs(random.nextGaussian()) / npop);
                    if (dfs[i].isJDF()) {
                        for (Variable nvar : dfs[i].getNonEnumVars()) {
                            dfs[i].setDistrib(nvar, new GaussianDistrib(random.nextGaussian() * random.nextInt(100), Math.abs(random.nextGaussian() * (random.nextInt(10) + 1))));
                        }
                    }
                } else {
                    int index = random.nextInt(npop);
                    dfs[i].setValue(index, Math.abs(random.nextGaussian()) / npop);
                    if (dfs[i].isJDF()) {
                        for (Variable nvar : dfs[i].getNonEnumVars()) {
                            dfs[i].setDistrib(index, nvar, new GaussianDistrib(random.nextGaussian() * random.nextInt(100), Math.abs(random.nextGaussian() * (random.nextInt(10) + 1))));
                        }
                    }
                }
            }
        }
        return dfs;
    }

    protected static AbstractFactor getProductBenchmarked(FactorProductTree node) {
        if (node.getFactor() != null) {
            return node.getFactor();
        }
        AbstractFactor X = getProductBenchmarked(node.x);
        AbstractFactor Y = getProductBenchmarked(node.y);
        long startTime = System.nanoTime();
        AbstractFactor f = getProduct(X, Y);
        long endTime = System.nanoTime();
        for (int j = 0; j < 20; j++) {
            if (testProductIntegrity(j, X, Y, f) == false) {
                System.err.println("Test failed");
            }
        }
        int overlap = getOverlap(X, Y);
        int minevars = Math.min(X.nEVars, Y.nEVars);
        int maxevars = Math.max(Y.nEVars, Y.nEVars);
        System.out.println(maxevars + "\t" + minevars + "\t" + overlap + "\t" + (minevars == 0 ? 0.0 : overlap / (float) minevars) + "\t" + getComplexity(X, Y, false) + "\t" + getComplexity(X, Y, true) + "\t" + (endTime - startTime) / 100000.0);
        node.setFactor(f);
        return f;
    }

    protected static AbstractFactor getProductBenchmarked(AbstractFactor[] factors) {
        if (factors.length == 0) {
            return null;
        }
        AbstractFactor R = factors[0];
        for (int i = 1; i < factors.length; i++) {
            AbstractFactor X = R;
            AbstractFactor Y = factors[i];
            long startTime = System.nanoTime();
            R = getProduct(X, Y);
            long endTime = System.nanoTime();
            for (int j = 0; j < 20; j++) {
                if (testProductIntegrity(j, X, Y, R) == false) {
                    System.err.println("Test failed");
                }
            }
            int overlap = getOverlap(X, Y);
            int minevars = Math.min(X.nEVars, Y.nEVars);
            int maxevars = Math.max(Y.nEVars, Y.nEVars);
            System.out.println(maxevars + "\t" + minevars + "\t" + overlap + "\t" + (minevars == 0 ? 0.0 : overlap / (float) minevars) + "\t" + getComplexity(X, Y, false) + "\t" + getComplexity(X, Y, true) + "\t" + (endTime - startTime) / 100000.0);
        }
        return R;
    }

    protected static AbstractFactor productPool(AbstractFactor[] dfs, int option) {
        if (dfs.length == 0) {
            return null;
        }
        if (dfs.length == 1) {
            return dfs[0];
        }
        AbstractFactor f = null;
        long startTime = System.nanoTime();
        switch (option) {
            case POOL_OPTION_LINEAR:
                f = getProductBenchmarked(dfs);
                break;
            case POOL_OPTION_TREE:
                FactorProductTree tree = getProductTree(dfs);
                f = getProductBenchmarked(tree);
                break;
        }
        long endTime = System.nanoTime();
        System.out.println("\t\t\t\t\t\t\t" + (endTime - startTime) / 100000.0);
        if (f.nEVars > 0) {
            Random rand = new Random(endTime);
            int n = rand.nextInt(f.nEVars);
            Variable[] sumout = new Variable[n];
            for (int i = 0; i < n; i++) {
                sumout[i] = f.evars[rand.nextInt(n)];
            }
            if (rand.nextBoolean()) {
                getMargin(f, sumout);
            } else {
                getMaxMargin(f, sumout);
            }
            return f;
        }
        return f;
    }

    protected static boolean testProductIntegrity(long seed, AbstractFactor X, AbstractFactor Y, AbstractFactor PROD) {
        Random rand = new Random(seed);
        int p = rand.nextInt(PROD.getSize());
        Object[] pkey;
        if (PROD.getSize() == 1) {
            pkey = new Object[0];
        } else {
            pkey = PROD.getKey(p);
        }
        EnumVariable[] pevars = PROD.getEnumVars();
        EnumVariable[] xevars = X.getEnumVars();
        EnumVariable[] yevars = Y.getEnumVars();
        Object[] xkey = new Object[X.nEVars];
        Object[] ykey = new Object[Y.nEVars];
        for (int i = 0; i < pkey.length; i++) {
            if (!pevars[i].getDomain().isValid(pkey[i])) {
                return false;
            }
            int xi = -1;
            for (int j = 0; j < X.nEVars; j++) {
                if (xevars[j].equals(pevars[i])) {
                    xi = j;
                }
            }
            int yi = -1;
            for (int j = 0; j < Y.nEVars; j++) {
                if (yevars[j].equals(pevars[i])) {
                    yi = j;
                }
            }
            if (yi != -1) {
                ykey[yi] = pkey[i];
            }
            if (xi != -1) {
                xkey[xi] = pkey[i];
            }
        }
        double xval;
        if (xkey.length != 0) {
            xval = X.getValue(X.getIndex(xkey));
        } else {
            xval = X.getValue();
        }
        double yval;
        if (ykey.length != 0) {
            yval = Y.getValue(Y.getIndex(ykey));
        } else {
            yval = Y.getValue();
        }
        double pval;
        if (pkey.length == 0) {
            pval = PROD.getValue();
        } else {
            pval = PROD.getValue(p);
        }
        //System.out.print(p + "\t");
        return xval * yval == pval;
    }

    protected static void testCrossRef(long seed, Variable[] vars) {
        Random rand = new Random(seed);
        for (int t = 0; t < 20; t++) {
            Variable[] x = new Variable[rand.nextInt(vars.length - 1) + 1];
            Variable[] y = new Variable[rand.nextInt(vars.length - 1) + 1];
            for (int i = 0; i < x.length; i++) {
                x[i] = vars[rand.nextInt(vars.length)];
            }
            for (int i = 0; i < y.length; i++) {
                y[i] = vars[rand.nextInt(vars.length)];
            }
            x = getNonredundant(x);
            y = getNonredundant(y);
            int[] xcross = new int[x.length];
            int[] ycross = new int[y.length];
            getCrossref(x, xcross, y, ycross);
            Variable[] xx = new Variable[x.length];
            Variable[] yy = new Variable[y.length];
            for (int i = 0; i < y.length; i++) {
                if (ycross[i] != -1) {
                    xx[ycross[i]] = y[i];
                }
            }
            for (int i = 0; i < x.length; i++) {
                if (xcross[i] != -1) {
                    yy[xcross[i]] = x[i];
                }
            }
            for (int i = 0; i < x.length; i++) {
                System.out.print(x[i].toString() + ":" + ((xx[i] == null) ? ("-\t") : (xx[i].toString() + "\t")));
            }
            System.out.println();
            for (int i = 0; i < y.length; i++) {
                System.out.print(y[i].toString() + ":" + ((yy[i] == null) ? ("-\t") : (yy[i].toString() + "\t")));
            }
            System.out.println();
            System.out.println();
        }
    }

    public static void main(String[] args) {
        System.out.println("maxEV\tminEV\tOverlap\tContain\tProduct\tPJoin\tTime (ms)");
        for (long seed = 0; seed < 200; seed++) {
            Variable[] vars = getVariablePool(seed, 10);
            // testCrossRef(seed, vars);
            
            AbstractFactor[] dfs = getFactorPool(seed, vars, 8);
            AbstractFactor f1 = productPool(dfs, POOL_OPTION_LINEAR);
            AbstractFactor f2 = productPool(dfs, POOL_OPTION_TREE);
            if (f1 == null && f2 == null) {
                continue;
            }
            if (f1.getSize() != f2.getSize()) {
                System.err.println("Invalid product size");
            }
            if (f1.getSize() == 1) {
                if (f1.getValue() < f2.getValue() * 0.999 || f1.getValue() > f2.getValue() * 1.001) {
                    System.err.println("Invalid atomic product: " + f1.getValue() + " v " + f2.getValue());
                    System.exit(1);
                }
            } else {
                for (int i = 0; i < f1.getSize(); i++) {
                    if (f1.getValue(i) < f2.getValue(i) * 0.999 || f1.getValue(i) > f2.getValue(i) * 1.001) {
                        System.err.println("Invalid product: " + f1.getValue(i) + " v " + f2.getValue(i));
                        System.exit(1);
                    }
                }
            }

        }
    }

    /**
     * To represent a pair of factors. Originally intended to cache products, currently not used.
     */
    static private class FactorProduct {
        final EnumVariable[] Xvars, Yvars;
        FactorProduct(EnumVariable[] Xvars, EnumVariable[] Yvars) {
            this.Xvars = Xvars; 
            this.Yvars = Yvars;
        }
        @Override
	public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || (obj.getClass() != this.getClass())) return false;
            FactorProduct fp = (FactorProduct)obj;
            return ((Xvars == fp.Xvars && Yvars == fp.Yvars) || ((Xvars == fp.Yvars) && (Yvars == fp.Xvars)));
        }
        @Override
        public int hashCode() {
            int hash = 7;
            hash = 31 * hash + (Xvars == null ? 0 : Arrays.hashCode(Xvars)) + (Yvars == null ? 0 : Arrays.hashCode(Yvars));
            return hash;
        }
    }
    
    /**
     * Class to structure the binary tree of factor products.
     */
    static public class FactorProductTree {
        private final FactorProductTree x, y;
        private AbstractFactor f = null;
        private EnumVariable[] evars = null;
        /**
         * Construct a node in the tree
         * @param x child
         * @param y child
         */
        FactorProductTree(FactorProductTree x, FactorProductTree y) {
            this.x = x;
            this.y = y;
            Variable[] vars = Factorize.getNonredundantSorted(Factorize.getConcat(x.getEnumVars(), y.getEnumVars()));
            this.evars = new EnumVariable[vars.length];
            for (int i = 0; i < vars.length; i ++)
                this.evars[i] = (EnumVariable) vars[i];
        }
        
        /**
         * Construct a leaf node in the tree
         * @param f the factor
         */
        FactorProductTree(AbstractFactor f) {
            this.x = null;
            this.y = null;
            this.f = f;
        }
        EnumVariable[] getEnumVars() {
            if (f == null)
                return this.evars;
            else
                return f.getEnumVars();
        }
        /**
         * Assign the factor to a node in the tree
         * @param f the factor
         */
        void setFactor(AbstractFactor f) {
            this.f = f;
        }
        AbstractFactor getFactor() {
            return f;
        }
    }
    
}
