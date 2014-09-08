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
package bn;

import java.io.Serializable;
import java.util.*;

/**
 * Class for Gaussian Density Table (GDT). This is a table for a continuous
 * variable, with each row specifying a single Gaussian density. The node has
 * one or more enumerable parents.
 *
 * @author m.boden
 */
public class GDT implements BNode, Serializable {

    private static final long serialVersionUID = 1L;

    public final int VARIANCE_UNTIED = 0;
    public final int VARIANCE_TIED_MAX = 1;
    public final int VARIANCE_TIED_POOLED = 2;
    
    private int tieVariances = VARIANCE_TIED_POOLED;
    

    final private Variable<Continuous> var;
    private GaussianDistrib prior = null;
    private EnumTable<GaussianDistrib> table = null;

    // Parameters used for training. Some of which are allocated prior to training, and then re-used to save time.
    
    private SampleTable<Double> countDouble = null; // the table that will contain all samples of the type "Double" during learning
    private SampleTable<Distrib> countDistrib = null; // the table that will contain all samples of the type "Distrib" during learning

    private double[] observed = new double[0];// observations are stored here AFTER sample collecting, some caching is possible (values and numbers there-of)
    private double[] prob = new double[0];    // probability of making observation
    private int[] row = new int[0];           // identifies to which "row" the observation belongs
    
    final private double[] means;       // save the means 
    final private double[] vars; 	// save the variances
    final private double[] n;           // save the numbers of samples
    
    private boolean relevant = false;
    private Set<String> tags = new HashSet<>();

    /**
     * Create a Gaussian density table for a variable. The variable is
     * conditioned on a set of Enumerable variables.
     *
     * @param var variable
     * @param parents parent variables
     */
    public GDT(Variable<Continuous> var, List<EnumVariable> parents) {
        this.var = var;
        int maxrows = 0;
        if (parents != null) {
            if (parents.size() > 0) {
                this.table = new EnumTable<>(parents);
                maxrows = this.table.getSize();
            }
        }
        means = new double[maxrows];
        vars = new double[maxrows];
        n = new double[maxrows];
    }

    /**
     * Create a Gaussian density table for a variable. The variable is
     * conditioned on a set of Enumerable variables.
     *
     * @param var variable
     * @param parents parent variables
     */
    public GDT(Variable<Continuous> var, EnumVariable... parents) {
        this(var, EnumVariable.toList(parents));
    }

    /**
     * Create a Gaussian prior for a variable. Note: the prior distribution is
     * not set (@see bn.GDT#put(GaussianDistrib)).
     * @param var variable
     */
    public GDT(Variable<Continuous> var) {
        this.var = var;
        int maxrows = 0;
        means = new double[maxrows];
        vars = new double[maxrows];
        n = new double[maxrows];
    }

    /**
     * Assign tags for this node.
     * @param tags
     */
    public void setTags(String... tags){
        for (String tag : tags)
            this.tags.add(tag);
    }

    /**
     * Get the tags for this node
     * @return set of tag names
     */
    public Set getTags(){
        return this.tags;
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
        } catch (EnumTableRuntimeException e) {
            throw new RuntimeException("Evaluation of GDT " + this.toString() + " failed since condition was not fully specified: " + e.getMessage());
        }
    }
    
    /**
     * Retrieve the distribution for this node with no parents.
     * @return the distribution of the variable for this root node
     */
    @Override
    public GaussianDistrib getDistrib() {
        return prior;
    }

    /**
     * Make a native Distrib instance out of a collection of samples.
     * @param samples
     * @return an instance of Distrib that can be used to populate this node.
     */
    public GaussianDistrib makeDistrib(Collection<Sample> samples) {
        if (samples == null)
            return new GaussianDistrib(0,0.01);
        double sum = 0;		// we keep track of the sum of observed values
        double tot = 0;		// we keep track of the total of counts
        double[] infobs = new double[samples.size()];
        double[] infprb = new double[samples.size()];
        // go through observed samples
        int j = 0;
        for (Sample sample : samples) {// look at each distribution
            try {
                Double y = (Double) sample.instance;
                infobs[j] = y;        // actual value (or score)
                infprb[j] = sample.prob;            // p(class=key) i.e. the height of the density for this parent config  
                sum += infobs[j] * infprb[j];            // update the numerator of the mean calc
                tot += infprb[j];                   // update the denominator of the mean calc
            } catch (ClassCastException e1) {
                try {
                    Distrib d = (Distrib) sample.instance;
                    infobs[j] = (double) d.sample();        // actual value (or score)
                    infprb[j] = sample.prob;                   // p(class=key) i.e. the height of the density for this parent config  
                    sum += infobs[j] * infprb[j];				// update the numerator of the mean calc
                    tot += infprb[j];					// update the denominator of the mean calc
                } catch (ClassCastException e2) {
                    throw new RuntimeException("Evaluation of GDT distribution failed since sample was of unknown type: " + sample.instance);
                }
            }
            j++; 
        }                
        // calculate mean
        double mean = 0;
        if (tot > 0) 
            mean = sum / tot;
        // now for calculating the variance
        double diff = 0;
        for (int jj = 0; jj < j; jj++)
            diff += (mean - infobs[jj]) * (mean - infobs[jj]) * infprb[jj];
        double variance = Math.max(diff / tot, 0.01);
        return new GaussianDistrib(mean, variance);
    }
    
    /**
     * Make a FactorTable out of this GDT. If a variable is instantiated it will
     * be factored out.
     *
     * @param bn the BNet instance that can be used to check the status of nodes
     * so that factoring can be done (instantiation of variables are done for a
     * BNet node).
     * @return the FactorTable created from the GDT, provided instantiations of BN
     */
    @Override
    public Factor makeFactor(BNet bn) {
        List<EnumVariable> vars_old = this.getParents();
        Object varinstance = null;
        BNode cnode = bn.getNode(var);
        if (cnode != null) {
            varinstance = cnode.getInstance();
        }
        if (vars_old != null) { // there are parent variables
            Object[] searchkey = new Object[vars_old.size()];
            List<Variable> vars_new = new ArrayList<>(vars_old.size() + 1);
            for (int i = 0; i < vars_old.size(); i++) {
                EnumVariable parent = vars_old.get(i);
                BNode pnode = bn.getNode(parent);
                if (pnode != null)
                    searchkey[i] = pnode.getInstance();
                if (searchkey[i] == null)
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
                GaussianDistrib d = table.getValue(index);
                if (d != null) {
                    Object[] key = table.getKey(index);
                    int newcnt = 0;
                    for (int i = 0; i < key.length; i++) {
                        if (searchkey[i] == null) {
                            newkey[newcnt++] = key[i];
                        }
                    }
                    if (varinstance != null) { // the variable for this GDT is instantiated
                        ft.addFactor(newkey, d.get(varinstance));
                    } else { // the variable for this GDT is NOT instantiated...
                        ft.addFactor(newkey, 1.0);
                        ft.setDistrib(newkey, this.var, d);
                    }
                } else { // this entry is null
                    //
                }
            }
            return ft;
        } else { // no parents, just a prior
            if (varinstance != null) // instantiated prior is not possible to factorise
                return null;
            throw new RuntimeException("GDTs can not be factorised unless it has enumerable parent variables");
        }
    }

    /**
     * Make a FactorTable out of this GDT. If a variable is instantiated it will
     * be factored out.
     * If a parent is not relevant, it will not be included in the factor
     *
     *Marginalization technique requires updating
     *
     * @param bn the BNet instance that can be used to check the status of nodes
     * so that factoring can be done (instantiation of variables are done for a
     * BNet node).
     * @return the FactorTable created from the GDT, provided instantiations of BN
     */
    @Override
    public Factor makeFactor(BNet bn, boolean relevant) {
        List<EnumVariable> vars_old = this.getParents();
        Object varinstance = null;
        BNode cnode = bn.getNode(var);
        if (cnode != null) {
            varinstance = cnode.getInstance();
        }
        if (vars_old != null) { // there are parent variables
            Object[] searchkey = new Object[vars_old.size()];
            List<Variable> vars_new = new ArrayList<>(vars_old.size() + 1);
            List<EnumVariable> irrel_pars = new ArrayList<>(); //irrelevant parents
            for (int i = 0; i < vars_old.size(); i++) {
                EnumVariable parent = vars_old.get(i);
                // Record irrelevant parents to sum out
                //FIXME when should a parent be removed? Allow it to influence factor table then remove it?
                // If parent is evidenced it will not be included in factor table
                if (!bn.getNode(parent).isRelevant() && bn.getNode(parent).getInstance() == null) {
                	irrel_pars.add(parent);
                }
                BNode pnode = bn.getNode(parent);
                if (pnode != null)
                    searchkey[i] = pnode.getInstance();
                if (searchkey[i] == null)
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
                GaussianDistrib d = table.getValue(index);
                if (d != null) {
                    Object[] key = table.getKey(index);
                    int newcnt = 0;
                    for (int i = 0; i < key.length; i++) {
                        if (searchkey[i] == null) {
                            newkey[newcnt++] = key[i];
                        }
                    }
                    if (varinstance != null) { // the variable for this GDT is instantiated
                        ft.addFactor(newkey, d.get(varinstance));
                    } else { // the variable for this GDT is NOT instantiated...
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
            throw new RuntimeException("GDTs can not be factorised unless it has enumerable parent variables");
        }
    }
    
    /**
     * Get the conditional probability of the variable (represented by this GDT)
     * when set to a specified value.
     *
     * @param key condition
     * @param value the value of the variable represented by this GDT
     * @return the probability density of the variable set to specified value
     */
    public Double get(Object[] key, Object value) {
        if (key == null) {
            if (prior != null) {
                return prior.get(value);
            }
            return null;
        }
        GaussianDistrib d = table.getValue(key);
        if (d != null) {
            Double p = d.get(value);
            return p;
        }
        return null;
    }

    /**
     * Get the conditional probability of the variable (represented by this GDT)
     * when set to a specified value.
     *
     * @param value the value of the variable represented by this GDT
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
        GaussianDistrib d = table.getValue(key);
        if (d != null) {
            Double p = d.get(value);
            return p;
        }
        return null;
    }

    /**
     * Get the prior probability of the variable (represented by this GDT)
     *
     * @param value the value of the variable represented by this CDT
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
     * Get the conditional probability (density if continuous) of the variable
     * (represented by this CDT).
     *
     * @return the probability
     */
//	public GaussianDistrib get(Object[] key) {
//		return table.getValue(key);
//	}
    @Override
    public EnumTable getTable() {
        return table;
    }

    /**
     * Set entry (or entries) of the CDT to the specified probability
     * distribution
     *
     * @param key the boolean key (probabilistic condition)
     * @param distr the distribution
     */
    public void put(Object[] key, GaussianDistrib distr) {
        table.setValue(key, distr);
    }

    /**
     * Set entry (or entries) of the CDT to the specified probability
     * distribution
     *
     * @param index the index for the key (probabilistic condition)
     * @param distr the distribution
     */
    public void put(int index, GaussianDistrib distr) {
        table.setValue(index, distr);
    }

    /**
     * Set entry (or entries) of the CDT to the specified probability
     * distribution
     *
     * @param distr the distribution
     * @param key the boolean key (probabilistic condition)
     */
    public void put(GaussianDistrib distr, Object... key) {
        table.setValue(key, distr);
    }

    public void put(GaussianDistrib distr) {
        prior = distr;
    }

    @Override
    public String toString() {
        List<EnumVariable> parents = table.getParents();
        StringBuilder sbuf = new StringBuilder();
        for (int i = 0; i < parents.size(); i++) {
            sbuf.append(parents.get(i).getName()).append(i < parents.size() - 1 ? "," : "");
        }
        return "GDT(" + getName() + "|" + sbuf.toString() + ")" + (getInstance() == null ? "" : "=" + getInstance());
    }

    protected String formatTitle() {
        return String.format(" %10s", var.getName());
    }

    protected String formatValue(GaussianDistrib x) {
        return String.format("<%s>", x.toString());
    }

    @Override
    public void print() {
        if (table.nParents > 0) // variables in condition
        {
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

    private Double instance = null;

    @Override
    public void setInstance(Object value) {
        try {
            instance = (Double) value;
        } catch (ClassCastException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void resetInstance() {
        instance = null;
    }

    @Override
    public Double getInstance() {
        return instance;
    }
    
    @Override
    public void countInstance(Object[] key, Object value, Double prob) {
        if (this.isRoot()) {
            throw new RuntimeException("GDT can not be trained as root");
            // same process as for entries with parents, just a single queue of observations...
        } else {
            if (countDouble == null) // create countDouble table if none exists
                countDouble = new SampleTable<>(this.getParents());
            if (countDistrib == null)
                countDistrib = new SampleTable<>(this.getParents());
            try {
                countDouble.count(key, (Double)value, prob);
            } catch (ClassCastException e) {
                countDistrib.count(key, (Distrib)value, prob);
            }
        }
    }
    
    @Override
    public void countInstance(Object[] key, Object value) {
        countInstance(key, value, 1.0);
    }

    /**
     * Set this CDT to be trained when the Bayesian network it is part of is
     * trained. A CDT is trainable (true) by default.
     *
     * @param status true if trainable, false otherwise
     */
    public void setTrainable(boolean status) {
        trainable = status;
    }

    protected boolean trainable = true;

    /**
     * Check if this GDT should be trained or not
     */
    @Override
    public boolean isTrainable() {
        return trainable;
    }

    /**
     * Put random entries in the GDT if not already set.
     * @param seed
     */
    @Override
    public void randomize(long seed) {
        Random rand = new Random(seed);
        if (table == null) {
            if (prior == null)
                prior = new GaussianDistrib(rand.nextGaussian(), rand.nextDouble());
        } else {
            int nrows = table.getSize();
            for (int i = 0; i < nrows; i++) {
                if (!table.hasValue(i))
                    table.setValue(i, new GaussianDistrib(rand.nextGaussian(), rand.nextDouble()));
            }
        }
    }

    /*	public void randomize(Object[] observations, int seed) {
     // use observations to initialise this GDT
     // we do not have access to parents so this is only a rough estimate
     double min=0, max=0, sum=0, var=0;
     for (int i=0; i<observations.length; i++) {
     Double y=(Double)observations[i];
     sum+=y;
     if (i==0)
     max=min=y;
     else {
     if (y>max)
     max=y;
     else if (y<min)
     min=y;
     }
     }
     double mean=sum/observations.length;
     for (int i=0; i<observations.length; i++) {
     Double y=(Double)observations[i];
     var+=(mean-y)*(mean-y);
     }
     var/=observations.length;
     int nrows=getMaxIndex();
     if (nrows<2)
     put(new Gaussian(mean, var));
     else {
     double range=max-min;
     double stepsize=range/nrows;
     for (int i=0; i<nrows; i++) {
     put(entry(i), new Gaussian((max-stepsize*i)-stepsize/2, var));
     }
     }
     }
     */
    /**
     * Couple/uncouple variances.
     *     public final int VARIANCE_UNTIED = 0;
     *     public final int VARIANCE_TIED_MAX = 1;
     *     public final int VARIANCE_TIED_POOLED = 2;
     * (see Hastie & Tibshirani).
     *
     * @param status variance update policy
     */
    public void setTieVariances(int status) {
        tieVariances = status;
    }

    public int getNumberObservedDistrib() {
        int number = 0;
        if (countDistrib == null)
            return 0;
        for (List<Sample<Distrib>> samplesDistrib : countDistrib.table.getValues()) {
            number += samplesDistrib.size();
        }
        return number;
    }
    
    public int getNumberObservedSample() {
        int number = 0;
        if (countDouble == null)
            return 0;
        for (List<Sample<Double>> samplesDouble : countDouble.table.getValues()) {
            number += samplesDouble.size();
        }
        return number;
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
        int maxrows = table.getSize();
        int nSample = 20;                        // how many samples that should be generated for observed distributions
        double maxVar = 0;                      // the largest variance of any class  
        double middleMean = 0; 			// the mean of all values
        double middleVar = 0;			// the variance of all values
        double middleTot = 0;			// the sum of counts for all parent configs

        int nObservedDouble = getNumberObservedSample();
        int nObservedDistrib = getNumberObservedDistrib();
        int nTotal = nObservedDouble + nObservedDistrib * nSample;
        
        if (observed.length != nTotal) {
            observed = new double[nTotal];
            prob = new double[nTotal];
            row = new int[nTotal]; // the row in the table to which the observation belong
        }
        // Go through each possible row, each with a unique combination of parent values (no need to know parent values actually)
        int j = 0;                  // sample count
        for (int index = 0; index < maxrows; index ++) {

            List<Sample<Distrib>> samplesDistrib = null;
            if (countDistrib != null)
                samplesDistrib = countDistrib.get(index);
            List<Sample<Double>> samplesDouble = null;
            if (countDouble != null)        
                samplesDouble = countDouble.get(index);
            double sum = 0;		// we keep track of the sum of observed values for a specific parent config weighted by count, e.g. 4x0.5 + 3x1.3 + ... 
            double tot = 0;		// we keep track of the total of counts for a specific parent config so we can compute the mean of values, e.g. there are 23 counted for parent is "true"
            int jStart = j;             // start index for samples in the row
            if (samplesDistrib == null && samplesDouble == null) 
                continue;                 // no samples of any kind
            // go through observed distributions... if any
            if (samplesDistrib != null) {
                for (Sample<Distrib> sample : samplesDistrib) {// look at each distribution
                    for (int s = 0; s < nSample; s ++) {
                        observed[j] = (Double)sample.instance.sample();        // actual value (or score)
                        prob[j] = sample.prob / nSample;                   // p(class=key) i.e. the height of the density for this parent config  
                        sum += observed[j] * prob[j];				// update the numerator of the mean calc
                        tot += prob[j];					// update the denominator of the mean calc
                        row[j] = index;
                        j++; 
                    }
                }
            }
            // go through actual values...
            if (samplesDouble != null) {
                for (Sample<Double> sample : samplesDouble) {// look at each entry
                    observed[j] = sample.instance;        // actual value (or score)
                    prob[j] = sample.prob;            // p(class=key) i.e. the height of the density for this parent config  
                    sum += observed[j] * prob[j];            // update the numerator of the mean calc
                    tot += prob[j];                   // update the denominator of the mean calc
                    row[j] = index;
                    j++; 
                }
            }
            n[index] = tot; // save the number of possibly fractional samples on which the estimates were based
            // calculate mean
            means[index] = sum / tot;
            // now for calculating the variance
            double diff = 0;
            for (int jj = 0; jj < j - jStart; jj++) {
                diff += (means[index] - observed[jStart + jj]) * (means[index] - observed[jStart + jj]) * prob[jStart + jj];
            }
            vars[index] = diff / tot;
            if (vars[index] < 0.01) {
                vars[index] = 0.01;
            }
            if (vars[index] > maxVar) {
                maxVar = vars[index];
            }
            // note the same key/index for both the CPT and the Sample table
            middleTot += tot;
            middleMean += sum;
        }
        middleMean /= middleTot;
        countDistrib = null;    // reset counts
        countDouble = null;     // reset counts

        if (tieVariances == VARIANCE_UNTIED) { // if we use the individual variances
            for (int i = 0; i < maxrows; i ++) {
                if (n[i] > 0)
                    this.put(i, new GaussianDistrib(means[i], vars[i]));
            }
        } else { // re-compute variances if they need to be tied
            // there are different ways of dealing with this
            if (tieVariances == VARIANCE_TIED_MAX) {
                // (1) simply use the max of the existing variances
                for (int i = 0; i < maxrows; i ++) {
                    if (n[i] > 0)
                        this.put(i, new GaussianDistrib(means[i], maxVar));
                }
            } else if (tieVariances == VARIANCE_TIED_POOLED) { 
                // (2) use the pooled existing variances (http://en.wikipedia.org/wiki/Pooled_variance)
                double num = 0.0;
                double denom = 0.0;
                for (int i = 0; i < maxrows; i ++) {
                    if (n[i] >= 1) {
                        num += (n[i] - 1) * vars[i];
                        denom += (n[i] - 1);
                    }
                }
                for (int i = 0; i < maxrows; i ++) {
                    if (n[i] > 0) 
                        this.put(i, new GaussianDistrib(means[i], num / denom));
                }
            } else {
                // (3) compute the variance of all the values (Hastie and Tibshirani did this--but I have not had great success with this /MB)
                throw new RuntimeException("This variant of tied variance is not yet implemented");
                // ...
            }
        }
    }

    @Override
    public String getName() {
        return getVariable().getName();
    }

    @Override
    public Variable<Continuous> getVariable() {
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
        return "GDT";
    }

    @Override
    public String getStateAsText() {
        StringBuilder sbuf = new StringBuilder("\n");
        if (isRoot()) {
            GaussianDistrib d = prior;
            if (d != null) {
                sbuf.append("").append(d.getMean()).append(", ").append(d.getVariance()).append(";\n");
            }
        } else {
            for (int i = 0; i < table.getSize(); i++) {
                GaussianDistrib d = table.map.get(new Integer(i));
                if (d != null) {
                    sbuf.append(i).append(": ");	// use index as key because values above can be of different non-printable types
                    sbuf.append("").append(d.getMean()).append(", ").append(d.getVariance()).append("; (");
                    // If we want to *see* the key, may not work well for some non-printable types
                    Object[] key = table.getKey(i);
                    for (int j = 0; j < key.length; j++) {
                        if (j < key.length - 1) {
                            sbuf.append(key[j]).append(", ");
                        } else {
                            sbuf.append(key[j]).append(")\n");
                        }
                    }
                }
            }
        }
        return sbuf.toString();
    }

    @Override
    public boolean setState(String dump) {
        if (isRoot()) {
            String[] line = dump.split(";");
            if (line.length >= 1) {
                String[] y = line[0].split(",");
                if (y.length == 2) {
                    double[] distrib = new double[y.length];
                    try {
                        for (int i = 0; i < distrib.length; i++) {
                            distrib[i] = Double.parseDouble(y[i]);
                        }
                    } catch (NumberFormatException e) {
                        e.printStackTrace();
                        return false;
                    }
                    this.put(new GaussianDistrib(distrib[0], distrib[1]));
                    return true;
                }
            }
        } else {
            for (String line : dump.split("\n")) {
                line = line.trim();
                // 0: 0.4, 0.6; (true, true)
                String[] specline = line.split(";");
                if (specline.length >= 1) {
                    String[] parts = specline[0].split(":");
                    if (parts.length >= 2) {
                        try {
                            int index = Integer.parseInt(parts[0]);
                            String[] y = parts[1].split(",");
                            if (y.length == 2) {
                                double[] distrib = new double[y.length];
                                try {
                                    for (int i = 0; i < distrib.length; i++) {
                                        distrib[i] = Double.parseDouble(y[i]);
                                    }
                                } catch (NumberFormatException e) {
                                    e.printStackTrace();
                                    return false;
                                }
                                this.put(table.getKey(index), new GaussianDistrib(distrib[0], distrib[1]));
                            }
                        } catch (NumberFormatException e) {
                            System.err.println("Number format wrong and ignored: " + line);
                        }
                    }
                }
            }
        }
        return false;
    }

    public boolean isRelevant() {
		return relevant;
	}

	public void setRelevant(boolean relevant) {
		this.relevant = relevant;
	}
    /**
     * @param args
     */
    public static void main(String[] args) {
        Variable<Continuous> v1 = Predef.Real();
        EnumVariable v2 = Predef.Boolean();
        EnumVariable v3 = Predef.Boolean();

        GDT gdt1 = new GDT(v1, new EnumVariable[]{v2, v3});
        gdt1.put(new Object[]{true, false}, new GaussianDistrib(0.0, 1.0));
        gdt1.print();
        System.out.println(gdt1.get(new Object[]{true, false}, 0.5));
    }

}
