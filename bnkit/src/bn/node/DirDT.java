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
package bn.node;

import bn.BNode;
import bn.Distrib;
import bn.EnumDistrib;
import bn.factor.Factor;
import bn.Sample;
import bn.SampleTable;
import bn.TiedNode;
import dat.EnumVariable;
import dat.Variable;
import dat.EnumTable;
import dat.Enumerable;
import bn.prob.DirichletDistrib;
import bn.factor.AbstractFactor;
import bn.factor.DenseFactor;
import bn.factor.Factorize;
import java.io.Serializable;
import java.util.*;

/**
 * Class for Dirichlet Density Table (DirDT). This is a table for a variable that can take enumerable 
 * distributions as values, with each row specifying a single Dirichlet density, specifying what 
 * distributions it produces. The node has one or more enumerable parents.
 *
 * @author m.boden
 */
public class DirDT implements BNode, TiedNode, Serializable {

    private static final long serialVersionUID = 1L;
    
    final private Variable<EnumDistrib> var;
    private DirichletDistrib prior = null;
    private EnumTable<DirichletDistrib> table = null;

    // Parameters used for training. Some of which are allocated prior to training, and then re-used to save time.
    private SampleTable<EnumDistrib> count = null; // the table that will contain all samples of the type "EnumDistrib" during learning
    
    private boolean relevant = false;
    private EnumDistrib instance = null; // the value this node takes, null if unspecified

    private DirDT tieSource;
    
    /**
     * Create a Dirichlet density table for a variable. The variable is
     * conditioned on a set of enumerable variables.
     *
     * @param var variable
     * @param parents parent variables
     */
    public DirDT(Variable<EnumDistrib> var, List<EnumVariable> parents) {
        this.var = var;
        if (parents != null) {
            if (parents.size() > 0) {
                this.table = new EnumTable<>(parents);
                this.prior = null;
                this.count = new SampleTable(parents);
            }
        }
    }

    /**
     * Create a Dirichlet density table for a variable. The variable is
     * conditioned on a set of enumerable variables.
     *
     * @param var variable
     * @param parents parent variables
     */
    public DirDT(Variable<EnumDistrib> var, EnumVariable... parents) {
        this(var, EnumVariable.toList(parents));
    }

    /**
     * Retrieve the distribution for this node that applies GIVEN the parents' instantiations.
     * Requires all parent nodes to be instantiated.
     * @param key the parent values
     * @return the distribution of the variable for this node
     */
    @Override
    public Distrib getDistrib(Object[] key) {
        if (this.table == null || key == null)
            return this.getDistrib();
        try {
            return this.table.getValue(key);
        } catch (RuntimeException e) {
            throw new RuntimeException("Evaluation of DirDT " + this.toString() + " failed since condition was not fully specified: " + e.getMessage());
        }
    }
    
    /**
     * Retrieve the distribution for this node with no parents.
     * @return the distribution of the variable for this root node
     */
    @Override
    public DirichletDistrib getDistrib() {
        return prior;
    }

    /**
     * Make a FactorTable out of this DirDT. If a variable is instantiated it will
     * be factored out.
     * If a parent is not relevant, it will not be included in the factor
     *
     * Marginalization technique requires updating.
     *
     * @param relevant only include relevant nodes, with instantiations if available
     * @return the FactorTable created from the DirDT, provided instantiations of BN
     */
    @Override
    public Factor makeFactor(Map<Variable, Object> relevant) {
        List<EnumVariable> vars_old = this.getParents();
        Variable myvar = this.getVariable();
        // get value of this node if any assigned
        Object varinstance = relevant.get(myvar); 
        if (vars_old != null) { // there are parent variables
            Object[] searchkey = new Object[vars_old.size()];
            List<Variable> vars_new = new ArrayList<>(vars_old.size() + 1);
            List<EnumVariable> irrel_pars = new ArrayList<>(); //irrelevant parents
            for (int i = 0; i < vars_old.size(); i++) {
                EnumVariable parent = vars_old.get(i);
                boolean parent_is_relevant = relevant.containsKey(parent);
                // Record irrelevant parents to sum out
                //FIXME when should a parent be removed? Allow it to influence factor table then remove it?
                // If parent is evidenced it will not be included in factor table; it is removed later through marginalization
                if (!parent_is_relevant) 
                    irrel_pars.add(parent);
                else
                    searchkey[i] = relevant.get(parent);
                if (searchkey[i] == null) // new factor needs to include this variable (to be summed out before returned if irrelevant)
                    vars_new.add(parent);
            }
            Factor ft;
            if (varinstance == null)
                vars_new.add(this.var);
            ft = new Factor(vars_new);
            if (varinstance != null)
                ft.evidenced = true;
            int[] indices = table.getIndices(searchkey);
            Object[] newkey = new Object[ft.getNEnum()];
            for (int index : indices) {
                DirichletDistrib d = table.getValue(index);
                if (d != null) {
                    Object[] key = table.getKey(index);
                    int newcnt = 0;
                    for (int i = 0; i < key.length; i++) {
                        if (searchkey[i] == null) {
                            newkey[newcnt++] = key[i];
                        }
                    }
                    if (varinstance != null) { // the variable for this DirDT is instantiated
                        ft.addFactor(newkey, d.get(varinstance));
                    } else { // the variable for this DirDT is NOT instantiated...
                        ft.addFactor(newkey, 1.0);
                        ft.setDistrib(newkey, this.var, d);
                    }
                } else { // this entry is null
                    //
                }
            }
            if (!irrel_pars.isEmpty()) {
            	ft = ft.marginalize(irrel_pars);
            }
            return ft;
        } else { // no parents, just a prior
            if (varinstance != null) // instantiated prior is not possible to factorise
                return null;
            throw new RuntimeException("DirDT can not be factorised unless it has enumerable parent variables");
        }
    }
    /**
     * Make factor out of this DirDT.
     * This method will deconstruct the condition, accounting for which variables that are relevant 
     * (as nominated by the caller of this function, e.g. inference algorithm) and those that are
     * instantiated. Instantiated and irrelevant variables are removed before the factor is returned.
     * @param relevant a map containing all relevant variables as keys, and values which are non-null if the variable
     * is instantiated
     * @return the factor
     */
    @Override
    public AbstractFactor makeDenseFactor(Map<Variable, Object> relevant) {
        List<EnumVariable> parents = this.getParents();
        Variable myvar = this.getVariable();
        // get value of this node if any assigned
        Object varinstance = relevant.get(myvar); 
        if (parents != null) { // there are parent variables
            Object[] searchcpt = new Object[parents.size()];
            List<Variable> fvars = new ArrayList<>(parents.size() + 1); // factor variables
            List<EnumVariable> sumout = new ArrayList<>();  // irrelevant variables to be summed out later
            for (int i = 0; i < parents.size(); i++) {
                EnumVariable parent = parents.get(i);
                // If parent is evidenced it will not be included in factor table; 
                // Record irrelevant parents to sum out: removed later through marginalization
                if (!relevant.containsKey(parent)) 
                    sumout.add(parent);
                else
                    searchcpt[i] = relevant.get(parent);
                if (searchcpt[i] == null) // new factor will include this variable
                    fvars.add(parent);
            }
            if (varinstance == null) {
                fvars.add(myvar); // add to factor, but note this is a non-enumerable variable so handled differently inside
            }
            Variable[] vars_arr = new Variable[fvars.size()];
            fvars.toArray(vars_arr);
            AbstractFactor ft = new DenseFactor(vars_arr);
            EnumVariable[] evars = ft.getEnumVars(); // the order may have changed; excludes non-enums
            int[] xcross = new int[parents.size()];
            int[] ycross = new int[evars.length];
            table.crossReference(xcross, evars, ycross);
            // set factor to be "evidenced" is there was an evidence used
            if (varinstance != null) {
                ft.evidenced = true;
            } else {
                for (Object instcpt : searchcpt) {
                    if (instcpt != null) {
                        ft.evidenced = true;
                        break;
                    }
                }
            }
            int[] indices = table.getIndices(searchcpt);
            Object[] fkey = new Object[evars.length];
            for (int index : indices) {
                DirichletDistrib d = table.getValue(index);
                if (d != null) { // there is a distribution associated with this entry in the CPT
                    Object[] cptkey = table.getKey(index); // work out the condition for this entry
                    for (int i = 0; i < cptkey.length; i++) {
                        if (xcross[i] != -1)
                            fkey[xcross[i]] = cptkey[i];
                    }
                    if (varinstance != null) { // the variable for this DirDT is instantiated
                        if (fkey.length == 0) // and the parents are too
                            ft.setValue(d.get(varinstance));
                        else
                            ft.setValue(fkey, d.get(varinstance));
                    } else { // the variable for this DirDT is NOT instantiated so we put it in the JDF
                        if (fkey.length == 0) { // but the parents are instantiated
                            ft.setValue(1.0); 
                            ft.setDistrib(myvar, d);
                        } else {
                            ft.setValue(fkey, 1.0);
                            ft.setDistrib(fkey, myvar, d);
                        }
                    }
                } 
            }
            if (!sumout.isEmpty()) {
                Variable[] sumout_arr = new Variable[sumout.size()];
                sumout.toArray(sumout_arr);
            	ft = Factorize.getMargin(ft, sumout_arr);
            }
            return ft;
        } else { // no parents, just a prior
            if (varinstance != null) // instantiated prior is not possible to factorise
                return null;
            throw new RuntimeException("DirDTs can not be factorised unless it has enumerable parent variables");
        }
    }

    /**
     * Get the conditional probability of the variable (represented by this DirDT)
     * when set to a specified value.
     *
     * @param key condition
     * @param value the value of the variable represented by this DirDT
     * @return the probability density of the variable set to specified value
     */
    public Double get(Object[] key, Object value) {
        if (key == null) {
            if (prior != null) {
                return prior.get(value);
            }
            return null;
        }
        DirichletDistrib d = table.getValue(key);
        if (d != null) {
            Double p = d.get(value);
            return p;
        }
        return null;
    }

    /**
     * Get the conditional probability of the variable (represented by this DirDT)
     * when set to a specified value.
     *
     * @param value the value of the variable represented by this DirDT
     * @param key condition
     * @return the probability density of the variable set to specified value
     */
    public Double get(Object value, Object... key) {
        if (key == null) {
            if (prior != null) {
                return prior.get(value);
            }
            return null;
        }
        DirichletDistrib d = table.getValue(key);
        if (d != null) {
            Double p = d.get(value);
            return p;
        }
        return null;
    }

    /**
     * Get the prior probability of the variable (represented by this DirDT)
     *
     * @param value the value of the variable represented by this DirDT
     * @return the probability of the variable set to specified value
     */
    @Override
    public Double get(Object value) {
        if (prior != null) {
            return prior.get(value);
        }
        return null;
    }

    /**
     * @deprecated Do NOT use, will be removed in the future
     */
    @Override
    public EnumTable getTable() {
        return table;
    }

    /**
     * Set entry (or entries) of the DirDT to the specified probability
     * distribution
     *
     * @param key the boolean key (probabilistic condition)
     * @param distr the distribution
     */
    public void put(Object[] key, DirichletDistrib distr) {
        table.setValue(key, distr);
    }

    /**
     * Set entry (or entries) of the DirDT to the specified probability
     * distribution
     *
     * @param index the index for the key (probabilistic condition)
     * @param distr the distribution
     */
    public void put(int index, DirichletDistrib distr) {
        table.setValue(index, distr);
    }

    /**
     * Set entry (or entries) of the DirDT to the specified probability
     * distribution
     *
     * @param distr the distribution
     * @param key the boolean key (probabilistic condition)
     */
    public void put(DirichletDistrib distr, Object... key) {
        table.setValue(key, distr);
    }

    public void put(DirichletDistrib distr) {
        prior = distr;
    }

    @Override
    public String toString() {
        List<EnumVariable> parents = table.getParents();
        StringBuilder sbuf = new StringBuilder();
        for (int i = 0; i < parents.size(); i++) {
            sbuf.append(parents.get(i).getName()).append(i < parents.size() - 1 ? "," : "");
        }
        return "DirDT(" + getName() + "|" + sbuf.toString() + ")" + (getInstance() == null ? "" : "=" + getInstance());
    }

    protected String formatTitle() {
        return String.format(" %10s", var.getName());
    }

    protected String formatValue(DirichletDistrib x) {
        return String.format("<%s>", x.toString());
    }

    @Override
    public void print() {
        if (table.nParents > 0) { // variables in condition
            table.display();
        } else { // prior
            System.out.println(formatTitle());
            if (prior != null) {
                System.out.println(formatValue(prior));
            }
        }
    }

    @Override
    public boolean isRoot() {
        return table == null;
    }

    @Override
    public void setInstance(Object value) {
        try {
            instance = (EnumDistrib) value;
        } catch (ClassCastException e) {
            System.err.println("Invalid setInstance: " + this.getName() + " = " + value);
        }
    }

    @Override
    public void resetInstance() {
        instance = null;
    }

    @Override
    public EnumDistrib getInstance() {
        return instance;
    }
    
    @Override
    public void countInstance(Object[] key, Object value, Double prob) {
        if (prob == 0)
            return;
        if (this.isRoot()) {
            throw new RuntimeException("DirDT can not be trained as root");
            // same process as for entries with parents, just a single queue of observations...
        } else {
            count.count(key, (EnumDistrib)value, prob);
        }
    }
    
    @Override
    public void countInstance(Object[] key, Object value) {
        countInstance(key, value, 1.0);
    }

    /**
     * Set this DirDT to be trained when the Bayesian network it is part of is
     * trained. A DirDT is trainable (true) by default.
     *
     * @param status true if trainable, false otherwise
     */
    public void setTrainable(boolean status) {
        trainable = status;
    }

    protected boolean trainable = true;

    /**
     * Check if this DirDT should be trained or not
     */
    @Override
    public boolean isTrainable() {
        return trainable;
    }

    /**
     * Put random entries in the DirDT if not already set.
     * @param seed
     */
    @Override
    public void randomize(long seed) {
        Random rand = new Random(seed);
        if (table == null) {
            if (prior == null)
                prior = new DirichletDistrib((Enumerable)prior.getDomain(), (1+rand.nextGaussian()));
        } else {
            int nrows = table.getSize();
            for (int i = 0; i < nrows; i++) {
                if (!table.hasValue(i))
                    throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
            }
        }
    }

    /**
     * Find the best parameter setting for the observed data.
     * Note that this uses observed Double:s which are looked at directly, and 
     * observed Distrib:s which are used to stochastically generate samples. 
     * We cannot use distributions directly since they can be of any kind, as 
     * long as defined over a single continuous variable.
     */
    @Override
    public void maximizeInstance() {
        if (count.isEmpty()) {
            return;
        }
        Enumerable e = this.var.getDomain().getDomain();
        for (int index = 0; index < this.table.getSize(); index ++) {
            List<Sample<EnumDistrib>> samples = count.get(index);
            if (samples != null) {
                EnumDistrib[] dists = new EnumDistrib[samples.size()];
                DirichletDistrib dd = new DirichletDistrib(e, 1.0/e.size());
                for (int j = 0; j < samples.size(); j ++) {
                    Sample<EnumDistrib> sample = samples.get(j);
                    EnumDistrib d = (EnumDistrib)sample.instance;
                    dists[j] = d;
                    // should be using prob, but not so yet...
                    double prob = sample.prob;
                }
                // FIXME: alpha values should account for probability of this distribution
                double[] alphas = dd.findPrior(dists);
                this.put(index, dd);
            } else { // no counts
                this.table.removeValue(index);
            }
        }
        
        count.setEmpty();
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public String getName() {
        return getVariable().getName();
    }

    @Override
    public Variable<EnumDistrib> getVariable() {
        return var;
    }

    @Override
    public List<EnumVariable> getParents() {
        if (table == null) {
            return null;
        }
        List<EnumVariable> parents = table.getParents();
        return parents;
    }

    @Override
    public String getType() {
        return "DirDT";
    }

    @Override
    public String getStateAsText() {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public boolean setState(String dump) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    public boolean isRelevant() {
        return relevant;
    }

    public void setRelevant(boolean relevant) {
        this.relevant = relevant;
    }

    @Override
    public Distrib makeDistrib(Collection<Sample> samples) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    public void tieTo(BNode source) {
        DirDT src = (DirDT)source;
        if (!this.var.getDomain().equals(source.getVariable().getDomain()))
            throw new RuntimeException("Invalid sharing: " + var.getName() + " does not share domain with " + source.getVariable().getName());
        if (this.table.nParents != src.table.nParents)
            throw new RuntimeException("Invalid sharing: " + var.getName() + " has different number of parents from " + source.getVariable().getName());
        for (int i = 0; i < this.table.nParents; i ++) {
            Variable p1 = this.getParents().get(i);
            Variable p2 = src.getParents().get(i);
            if (!p1.getDomain().equals(p2.getDomain()))
                throw new RuntimeException("Invalid sharing: " + p1.getName() + " does not share domain with " + p2.getName());
        }
        this.tieSource = src;
        // need to tie:
        // - count (used during learning)
        // - prior (if applicable)
        // - table (if applicable)
        this.prior = src.prior;
        if (this.table.nParents > 0)
            this.table = src.table.retrofit(this.getParents());
    }
    public BNode getTieSource(){
        return this.tieSource;
    }


}