package bn.prob;

import bn.Distrib;
import dat.Domain;
import bn.EnumDistrib;
import dat.Enumerable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Implementation of Dirichlet distribution.
 * The class implements functionality for constructing Dirichlet for 
 * generating enumerable distributions of a specified domain.
 * The class implements sampling and ML estimation from enumerable distributions.
 * Some functionality is adapted from other sources (see copyright messages below).
 */
public class DirichletDistrib implements Distrib, Serializable {

    private static final long serialVersionUID = 1L;

    /** Dirichlet prior */
    private double[] alpha;
    private GammaDistrib[] gammas;
    /** Domain for enumerable distributions that can be generated from the Dirichlet and/or
     * learned from.     */
    private final Enumerable domain;
    
    /**
     * Construct a Dirichlet distribution with the given domain and
     * "alpha" parameter value for all dimensions.
     * @param domain the domain over which this distribution can generate distributions
     * @param same_alpha the alpha value that applies to all components
     */
    public DirichletDistrib(Enumerable domain, double same_alpha) {
        this.domain = domain;
        this.alpha = new double[domain.size()];
        Arrays.fill(this.alpha, same_alpha);
        gammas = new GammaDistrib[this.alpha.length];
        for (int i = 0; i < gammas.length; i++) {
            gammas[i] = new GammaDistrib(alpha[i], 1);
        }
    }

    /**
     * Construct a Dirichlet distribution using the provided enumerable distribution
     * (as location/centre of mass) and the sum of alphas (as concentration around location).
     * @param location the location (or centre) of mass, j.e. expected value of this Dirichlet distrib
     * @param alpha_sum the sum of alpha values which indicates the concentration around location
     */
    public DirichletDistrib(EnumDistrib location, double alpha_sum) {
        this.domain = location.getDomain();
        this.alpha = new double[domain.size()];
        for (int i = 0; i < alpha.length; i++) 
            alpha[i] = location.get(i) * alpha_sum;
        gammas = new GammaDistrib[alpha.length];
        for (int i = 0; i < gammas.length; i++) 
            gammas[i] = new GammaDistrib(alpha[i], 1);
    }

    /**
     * Construct a Dirichlet distribution with the given domain and
     * "alpha" parameter values.
     * @param domain the domain over which this distribution can generate distributions
     * @param alpha the alpha values that apply
     */
    public DirichletDistrib(Enumerable domain, double... alpha) {
        if (domain.size() != alpha.length)
            throw new RuntimeException("Invalid distribution");
        this.domain = domain;
        this.alpha = alpha;
        gammas = new GammaDistrib[this.alpha.length];
        for (int i = 0; i < gammas.length; i++) {
            gammas[i] = new GammaDistrib(alpha[i], 1);
        }
    }
    
    /** 
     * Construct a Dirichlet distribution based on a magnitude and a multinomial
     * @param domain the enumerable domain 
     * @param m The concentration or magnitude of the Dirichlet: sum_i alpha_i
     * @param p The location of mass j.e. expected probability distribution: p_i = alpha_i / m
     */
    public DirichletDistrib(Enumerable domain, double[] p, double m) {
        if (domain.size() != p.length)
            throw new RuntimeException("Invalid distribution");
        this.domain = domain;
        this.alpha = new double[p.length];
        for (int i = 0; i < p.length; i ++)
            this.alpha[i] = p[i] * m;
    }
    
    /**
     * Return sum of alpha (a*).
     * @return sum of alphas
     */
    public double getSum() {
        double sum = 0;
        for (double a : alpha)
            sum += a;
        return sum;
    }
    
    /**
     * Set new prior parameters for distribution.
     * @param alpha new prior
     */
    public void setPrior(double[] alpha) {
        for (int i = 0; i < this.alpha.length; i ++) {
            this.alpha[i] = alpha[i];
            gammas[i] = new GammaDistrib(this.alpha[i], 1);
        }
    }
    
    public Domain getDomain() {
        return domain;
    }
    
    /**
     * Returns the probability of a vector of values from this distribution.
     * @param dist an enumerable distribution
     * @return the probability
     */
    @Override
    public double get(Object dist) {
        EnumDistrib d = (EnumDistrib) dist;
        double[] pdist = d.get();
//        double prob = 1.0;
//        for (int j = 0; j < pdist.length; j++) {
//            double count_term = alpha[j];
//            double x = pdist[j];
//            prob *= Math.pow(x, count_term - 1);
//        }
//        prob /= normalize(alpha);
//        return prob;
        // for longer distributions, this must be done in log space
        double logp = 0.0;
        for (int i = 0; i < pdist.length; i++) {
            logp += Math.log(Math.pow(pdist[i], alpha[i] - 1));
        }
        double lnormalpha = lnormalize(alpha);
        logp -= lnormalpha;
        return Math.exp(logp);
    }

    /**
     * Returns an enumerable sampled from this distribution.
     * @return an enumerable distribution, chosen in proportion to its probability
     */
    @Override
    public Object sample() {
        double sum = 0.0;
        double[] sample = new double[alpha.length];
        for (int i = 0; i < alpha.length; i++) {
            GammaDistrib component = gammas[i];
            double y = component.sample();
            sum += y;
            sample[i] = y;
        }

        for (int i = 0; i < alpha.length; i++) {
            sample[i] /= sum;
        }
        return new EnumDistrib(domain, sample);
    }

    /**
     * Computes the normalization constant for a Dirichlet distribution with
     * the given parameters.
     * @param params list of parameters of a Dirichlet distribution
     * @return the normalization constant for such a distribution
     */
    public static final double normalize(double[] params) {
        double denom = 0.0;
        double numer = 1.0;
        for (double param: params) {
            numer *= GammaDistrib.gamma(param);
            denom += param;
        }
        denom = GammaDistrib.gamma(denom);
        return numer / denom;
    }
    
    /**
     * Computes the log of the normalization constant for a Dirichlet distribution with
     * the given parameters.
     * @param params a list of parameters of a Dirichlet distribution
     * @return the log of the normalization constant for such a distribution
     */
    public static final double lnormalize(double[] params) {
        double denom = 0.0;
        double lnumer = 0.0;
        for (double param: params) {
            lnumer += GammaDistrib.lgamma(param);
            denom += param;
        }
        double ldenom = GammaDistrib.lgamma(denom);
        double logret = lnumer - denom;
        return logret;
    }
    
    
    /**
     * String representation of the Dirichlet distribution, containing alpha values.
     * @return string representation of object
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < alpha.length; i ++) {
            if (i < alpha.length - 1)
                sb.append(String.format("%4.2f,", alpha[i]));
            else
                sb.append(String.format("%4.2f;", alpha[i]));
        }
        return sb.toString();
    }
    
    /**
     * Get the log likelihood of a single histogram of counts.
     * @param count the histogram
     * @return the log likelihood assigned to by this Dirichlet
     */
    public double logLikelihood(int[] count) {
        double alpha_star = getSum();
        int c_star = 0;
        for (int c : count)
            c_star += c;
        double ll = GammaDistrib.lgamma(alpha_star) - GammaDistrib.lgamma(alpha_star + c_star);
        for (int j = 0; j < alpha.length; j ++) 
            ll += (GammaDistrib.lgamma(alpha[j] + count[j]) - GammaDistrib.lgamma(alpha[j]));
        return ll;
    }

    /**
     * Get the log likelihood of a single histogram of counts.
     * @param count the histogram
     * @param q the location or centre of the probability mass of the Dirichlet
     * @param alpha_star the sum of alpha
     * @return the log likelihood assigned to by this Dirichlet
     */
    public static double logLikelihood(int[] count, double[] q, double alpha_star) {
        int c_star = 0;
        for (int c : count)
            c_star += c;
        double ll = GammaDistrib.lgamma(alpha_star) - GammaDistrib.lgamma(alpha_star + c_star);
        for (int j = 0; j < q.length; j ++) 
            ll += (GammaDistrib.lgamma(alpha_star * q[j] + count[j]) - GammaDistrib.lgamma(alpha_star * q[j]));
        return ll;
    }
    
    /**
     * Get the log likelihood of multiple histograms of counts
     * @param counts the histograms
     * @return the log likelihood of the histograms assigned to by this Dirichlet
     */
    public double logLikelihood(int[][] counts) {
        double sum = 0;
        for (int[] count : counts)
            sum += logLikelihood(count);
        return sum;
    }
    
    /**
     * Get the log likelihood of multiple histograms of counts.
     * 
     * Some of the code below is based on the Gibbs sampling approach reported on in 
     * XUGANG YE, YI-KUO YU and STEPHEN F. ALTSCHUL
     * On the Inference of Dirichlet Mixture Priors for Protein Sequence Comparison
     * JOURNAL OF COMPUTATIONAL BIOLOGY Volume 18, Number 8, 2011
     * Pp. 941–954 DOI: 10.1089/cmb.2011.0040
     * 
     * @param counts the histograms
     * @param q the location or centre of the probability mass of the Dirichlet
     * @param alpha_star the sum of alpha
     * @return the log likelihood of the histograms assigned to by the Dirichlet specified by q and alpha_star
     */
    public static double logLikelihood(int[][] counts, double[] q, double alpha_star) {
        double sum = 0;
        for (int[] count : counts)
            sum += logLikelihood(count, q, alpha_star);
        return sum;
    }
    
    public static double logLikelihood_1stDerivative(int[][] counts, double[] q, double alpha_star) {
        double sum = 0;
        for (int[] count : counts) {
            int c_star = 0;
            for (int c : count)
                c_star += c;
            double inner = GammaDistrib.digamma(alpha_star) - GammaDistrib.digamma(alpha_star + c_star);
            for (int j = 0; j < count.length; j ++) 
                inner += q[j] * (GammaDistrib.digamma(q[j] * alpha_star + count[j]) - GammaDistrib.digamma(q[j] * alpha_star));
            sum += inner;
        }
        return sum;
    }
    
    public static double logLikelihood_2ndDerivative(int[][] counts, double[] q, double alpha_star) {
        double sum = 0;
        for (int[] count : counts) {
            int c_star = 0;
            for (int c : count)
                c_star += c;
            double inner = GammaDistrib.trigamma(alpha_star) - GammaDistrib.trigamma(alpha_star + c_star);
            for (int j = 0; j < count.length; j ++) 
                inner += q[j] * q[j] * (GammaDistrib.trigamma(q[j] * alpha_star + count[j]) - GammaDistrib.trigamma(q[j] * alpha_star));
            sum += inner;
        }
        return sum;
    }
    
    public static double getAlphaSum_byNewton(int[][] counts, double[] q) {
        double x0 = 1.0; // make an informed choice?
        double eps = 0.000001; // zero for practical purposes
        double fprime = logLikelihood_1stDerivative(counts, q, x0);
        while (fprime > eps) {
            double fdblprime = logLikelihood_2ndDerivative(counts, q, x0);
            double x = x0 - fprime / fdblprime;
            x0 = x;
            fprime = logLikelihood_1stDerivative(counts, q, x);
        }
        return x0;
    }
    
    public static double DL(int[][] data, EnumDistrib m, DirichletDistrib[] dds) {
        double outer = 0;
        for (int k = 0; k < data.length; k ++) {
            double inner = 0;
            for (int i = 0; i < dds.length; i ++) { 
                inner += (m.get(i) * Math.exp(dds[i].logLikelihood(data[k])));
            }
            outer += Math.log(inner);
        }
        return -outer;
    }
    
    
    public static void main(String[] args) {
        java.util.Random rand = new java.util.Random(1);
        DirichletDistrib dd = null;
        int nbins = 9;
        EnumDistrib[] eds = new EnumDistrib[nbins];
        for (int i = 0; i < eds.length; i ++) {
            eds[i] = EnumDistrib.random(Enumerable.nacid, rand.nextInt());
            System.out.println("D"+ i + " = " + eds[i]);
        }
        int N = 2000;
        int[][] data = generateData(eds, N, 1);
        for (int i = 0; i < N; i ++) {
            System.out.print("[" + i + "]\t= \t");
            for (int j = 0; j < data[i].length; j ++) 
                System.out.print(data[i][j] + "\t");
            System.out.println(" C = " + component[i]);
        }
        
        List[] bins = new ArrayList[nbins]; // hold components here
        DirichletDistrib[] dds = new DirichletDistrib[nbins];
        for (int i = 0; i < dds.length; i ++) {
            dds[i] = new DirichletDistrib(EnumDistrib.random(Enumerable.nacid, rand.nextInt()), 1);
            dds[i] = new DirichletDistrib(eds[i], 10);
        }
        EnumDistrib m = EnumDistrib.random(new Enumerable(nbins), rand.nextInt()); // mixing weights, add to 1
        m.setSeed(rand.nextInt());
        EnumDistrib p = EnumDistrib.random(new Enumerable(nbins), rand.nextInt()); // probability that sample belongs to bin
        p.setSeed(rand.nextInt());

        double dl_best = DL(data, m, dds);
        System.out.println("M = " + m);
        System.out.println("P = " + p);
        System.out.println("DL_best = " + dl_best);
        int no_update = 0;
        for (int round = 0; round < 100 && no_update < 20; round ++) {
            for (int i = 0; i < nbins; i ++)
                bins[i] = new ArrayList(); // start with empty bins
            int ncorrect = 0;
            for (int k = 0; k < N; k ++) {
                for (int i = 0; i < nbins; i ++) {
                    double prob = (m.get(i) * Math.exp(dds[i].logLikelihood(data[k])));
                    p.set(i, prob);
                }
                int bin = (Integer)p.sample();
                if (bin == component[k])
                    ncorrect ++;
//                System.out.println("P(" + k + ")\t= " + p + " picked " + bin + (bin == component[k]? "\t*": "\t "));
                bins[bin].add(data[k]);
            }
            System.out.println("Correct = " + ncorrect);
            
            for (int i = 0; i < nbins; i ++) {
                double[] location = new double[dds[i].domain.size()];
                double total = 0;
                int[][] hists = new int[bins[i].size()][];
                for (int j = 0; j < bins[i].size(); j ++) {
                    int[] hist = (int[])bins[i].get(j);
                    hists[j] = hist;
                    for (int jj = 0; jj < location.length; jj ++) {
                        location[jj] += hist[jj];
                        total += hist[jj];
                    }
                }
                for (int jj = 0; jj < location.length; jj ++) 
                    location[jj] /= total;
                double alpha_star = getAlphaSum_byNewton(hists, location);
                for (int jj = 0; jj < location.length; jj ++) 
                    location[jj] *= alpha_star;
                dds[i].setPrior(location); // just q, ignores a* (concentration)
                
                m.set(i, bins[i].size() / (double)N);
            }
            //System.out.println("M = " + m);
            
            double dl_cur = DL(data, m, dds);
            if (dl_cur < dl_best) {
                dl_best = dl_cur;
                no_update = 0;
            } else
                no_update ++;
                
            //System.out.println("DL_cur = " + dl_cur + "\tDL_best = " + dl_best);
        }
        System.out.println("M = " + m);
    }

    
    static int[] component = null;
    
    public static int[][] generateData(EnumDistrib[] components, int n, long seed) {
        java.util.Random rand = new java.util.Random(seed);
        int[][] data = new int[n][];
        component = new int[n];
        for (int i = 0; i < n; i ++) {
            int bin = rand.nextInt(components.length);
            component[i] = bin;
            int nsample = 10 + rand.nextInt(50); // max 60 values are drawn
            data[i] = new int[components[bin].getDomain().size()];
            for (int j = 0; j < nsample; j ++)
                data[i][components[bin].getDomain().getIndex(components[bin].sample())] += 1;
        }
        return data;
    }
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    /*
    The code below is based on Python code for MLE of Dirichlet copyright Max Sklar.
    // Source: https://github.com/maxsklar/research/tree/master/2014_05_Dirichlet/python
    // Paper: http://arxiv.org/pdf/1405.0099.pdf
    # Copyright 2013 Max Sklar
    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
    */
    
    /**
     * Find and set the optimal alpha parameters for a Dirichlet based on given instances of enumerable distribution.
     * @param dists array of enumerable distributions compatible with this Dirichlet
     */
    public void setPrior(EnumDistrib[] dists) {
        if (dists.length > 0) {
            double[] ss = DirichletDistrib.getSufficientStatistic(dists, alpha.length);
            setPrior(DirichletDistrib.findPrior(alpha, ss));
            return;
        }
        throw new RuntimeException("Invalid data for estimation of Dirichlet");
    }

    /**
     * Find the "sufficient statistic" for a group of multinomials.
     * Essentially, it's the average of the log probabilities.
     * @param multinomials instances of enumerable distributions
     * @param k number of values in distribution
     * @return 
     */ 
    public static double[] getSufficientStatistic(EnumDistrib[] multinomials, int k) {
        int N = multinomials.length;
        int K = k;
        double[] retVal = new double[K];
        for (EnumDistrib m : multinomials) {
            for (int cnt = 0; cnt < K; cnt ++) {
                double prob = m.get(cnt);
                double logProb = Math.log(prob);
                retVal[cnt] += logProb;
            }
        }
        for (int cnt = 0; cnt < K; cnt ++)
            retVal[cnt] /= N;
        return retVal;
    }

    /**
     * Find the log probability of the data for a given Dirichlet.
     * This is equal to the log probability of the data. up to a linear transform.
     * @param alphaTrials initial alpha
     * @param ss sufficient statistics for the data
     * @return the log probability
     */
    private static double logProbForMultinomials(double[] alphaTrials, double[] ss) {
        double alpha_sum = 0;
        double lgamma_sum = 0;
        double pq = 0;
        for (int i = 0; i < alphaTrials.length; i ++) {
            alpha_sum += alphaTrials[i];
            double lgamma_alpha = GammaDistrib.lgamma(alphaTrials[i]);
            lgamma_sum += lgamma_alpha;
            pq += (alphaTrials[i] * ss[i]);
        }
        double retVal = GammaDistrib.lgamma(alpha_sum) - lgamma_sum + pq;
        return retVal;
    }

    /**
     * Gives the derivative with respect to the log of prior.  
     * This will be used to adjust the loss.
     * @param alphaTrials initial alpha
     * @param ss sufficient statistics for the data
     * @return the gradient
     */
    private static double[] getGradientForMultinomials(double[] alphaTrials, double[] ss) {
        int K = alphaTrials.length;
        double alpha_sum = 0;
        for (int i = 0; i < alphaTrials.length; i ++) {
            alpha_sum += alphaTrials[i];
        }
        double C = GammaDistrib.digamma(alpha_sum);
        double[] retVal = new double[K];
        for (int i = 0; i < alphaTrials.length; i ++) {
            retVal[i] += (C + ss[i] - GammaDistrib.digamma(alphaTrials[i]));
        }
        return retVal;
    }

    /**
     * The Hessian is the sum of two matrices: 
     * a diagonal matrix and a constant-value matrix.
     * Two functions define them. This is the constant.
     * @param alpha alpha parameters
     * @return Hessian constant
     */
    private static double priorHessianConst(double[] alpha) { 
        double alpha_sum = 0;
        for (int i = 0; i < alpha.length; i ++) {
            alpha_sum += alpha[i];
        }
        return -GammaDistrib.trigamma(alpha_sum);
    }

    /**
     * The Hessian is the sum of two matrices: 
     * a diagonal matrix and a constant-value matrix.
     * Two functions define them. This is the diagonal.
     * @param alpha alpha parameters
     * @return Hessian diagonal
     */
    private static double[] priorHessianDiag(double[] alpha) { 
        double[] retVal = new double[alpha.length];
        for (int i = 0; i < alpha.length; i ++) 
            retVal[i] = GammaDistrib.trigamma(alpha[i]);
        return retVal;
    }
    
    /**
     * Compute the next value to try here.
     * http://research.microsoft.com/en-us/um/people/minka/papers/dirichlet/minka-dirichlet.pdf (eq 18)
     * @param alpha alpha parameters
     * @param hConst constant of Hessian
     * @param hDiag diagonal of Hessian
     * @param gradient the gradient 
     * @return step to change alpha
     */
    private static double[] getPredictedStep(double[] alpha, double hConst, double[] hDiag, double[] gradient) {
        int K = alpha.length;
        double[] retVal = new double[K];
        double numSum = 0.0;
        for (int doc = 0; doc < K; doc ++) 
            numSum += gradient[doc] / hDiag[doc];
        double denSum = 0.0;
        for (int doc = 0; doc < K; doc ++)  
            denSum += 1.0 / hDiag[doc];
        double b = numSum / ((1.0/hConst) + denSum);
        for (int doc = 0; doc < K; doc ++)
            retVal[doc] = (b - gradient[doc]) / hDiag[doc];
        return retVal;
    }

    /**
     * Use the Hessian on the log-alpha value to compute the next step.
     * @param alpha alpha parameters
     * @param hConst constant of Hessian
     * @param hDiag diagonal of Hessian
     * @param gradient the gradient 
     * @return step to change alpha
     */
    private static double[] getPredictedStepAlt(double[] alpha, double hConst, double[] hDiag, double[] gradient) {
        int K = alpha.length;
        double[] retVal = new double[K];
        double Z = 0;
        for (int cnt = 0; cnt < K; cnt ++)
            Z += alpha[cnt] / (gradient[cnt] - alpha[cnt]*hDiag[cnt]);
        Z *= hConst;
        double[] Ss = new double[K];
        double sumSs = 0;
        for (int cnt = 0; cnt < K; cnt ++) {
            Ss[cnt] = 1.0 / (gradient[cnt] - alpha[cnt]*hDiag[cnt]) / (1 + Z);
            sumSs += Ss[cnt];
        }
        for (int doc = 0; doc < K; doc ++)
            retVal[doc] = gradient[doc] / (gradient[doc] - alpha[doc]*hDiag[doc]) * (1 - hConst * alpha[doc] * sumSs);
        return retVal;
    }

    /**
     * Get the loss for current parameters (alpha-values) with the data represented by "sufficient statistics".
     * @param alpha alpha (parameters defining prior)
     * @param ss sufficient statistics for the data
     * @return loss
     */
    private static double getTotalLoss(double[] alpha, double[] ss) {
        return -1.0*logProbForMultinomials(alpha, ss);
    }

    /**
     * Use Hessian to predict step from current parameters (alpha-values).
     * @param alpha initial alpha
     * @param gradient gradient
     * @return step (change to alpha)
     */
    private static double[] predictStepUsingHessian(double[] alpha, double[] gradient) {
        double totalHConst = priorHessianConst(alpha);
        double[] totalHDiag = priorHessianDiag(alpha);		
        return getPredictedStep(alpha, totalHConst, totalHDiag, gradient);
    }
 
    /**
     * Use Hessian to predict step from current parameters (alpha-values).
     * @param alpha initial alpha
     * @param gradient gradient
     * @return step (change to alpha)
     */
    private static double[] predictStepLogSpace(double[] alpha, double[] gradient) {
        double totalHConst = priorHessianConst(alpha);
        double[] totalHDiag = priorHessianDiag(alpha);
        return getPredictedStepAlt(alpha, totalHConst, totalHDiag, gradient);
    }

    /**
     * Returns whether it's a good alpha, and the loss.
     * @param alpha alpha (parameters defining prior)
     * @param ss sufficient statistics for the data
     * @return loss (+infinite if invalid (negative or zero alpha)
     */
    private static double testTrialPriors(double[] alpha, double[] ss) {
        for (double a : alpha) {
            if (a <= 0)
                return Double.POSITIVE_INFINITY;
        }
        return getTotalLoss(alpha, ss);
    }

    private static double sqVectorSize(double[] v) {
        double s = 0;
        for (int doc = 0; doc < v.length; doc ++) 
            s += Math.pow(v[doc], 2);
        return s;
    }

    /**
     * Find the optimal alpha parameters for a Dirichlet based on given instances of enumerable distribution.
     * @param dists array of enumerable distributions compatible with this Dirichlet
     * @return the alpha values
     */
    public double[] findPrior(EnumDistrib[] dists) {
        if (dists.length > 0) {
            double[] ss = DirichletDistrib.getSufficientStatistic(dists, alpha.length);
            return DirichletDistrib.findPrior(alpha, ss);
        }
        throw new RuntimeException("Invalid data for estimation of Dirichlet");
    }
    
    /**
     * Find the optimal alpha parameters for a Dirichlet based on given instances of enumerable distribution.
     * @param dists array of enumerable distributions compatible with this Dirichlet
     * @param alphaStart initial alpha values
     * @return the optimal alpha values
     */
    public static double[] findPrior(EnumDistrib[] dists, double[] alphaStart) {
        double[] ss = DirichletDistrib.getSufficientStatistic(dists, alphaStart.length);
        return DirichletDistrib.findPrior(alphaStart, ss);
    }
    
    /**
     * Find the optimal alpha parameters for a Dirichlet based on sufficient statistics extracted for
     * data set.
     * @param alphaStart initial alpha values
     * @param ss sufficient statistics for the data
     * @return the optimal alpha values
     */
    public static double[] findPrior(double[] alphaStart, double[] ss) {
        double[] currentPriors = new double[alphaStart.length];
        System.arraycopy(alphaStart, 0, currentPriors, 0, currentPriors.length);
        double[] trialPriors = new double[currentPriors.length];
        // Only step in a positive direction, get the current best loss.
        double currentLoss = getTotalLoss(currentPriors, ss);
        double gradientToleranceSq = Math.pow(2, -20);
        double learnRateTolerance = Math.pow(2, -10);
        int count = 0;
        while (count < 1000) {
            count += 1;
            // Get the data for taking steps
            double[] gradient = getGradientForMultinomials(currentPriors, ss);
            double gradientSize = sqVectorSize(gradient);
            if (gradientSize < gradientToleranceSq) {
                // Converged with small gradient
                // System.out.println("Exited after " + count + " rounds with loss = " + currentLoss + " gradient = " + gradient);
                return currentPriors;
            }
            // First, try the second order method...
            double[] trialStep = predictStepUsingHessian(currentPriors, gradient);
            for (int a = 0; a < currentPriors.length; a ++)
                trialPriors[a] = currentPriors[a] + trialStep[a];
            double loss = testTrialPriors(trialPriors, ss);
            if (loss < currentLoss) {
                currentLoss = loss;
                // replace with new-found priors
                System.arraycopy(trialPriors, 0, currentPriors, 0, currentPriors.length);
                continue;
            }
            // Next, try first-order, iterative option...
            trialStep = predictStepLogSpace(currentPriors, gradient);
            for (int a = 0; a < currentPriors.length; a ++)
                trialPriors[a] = currentPriors[a] * Math.exp(trialStep[a]);
            loss = testTrialPriors(trialPriors, ss);
            if (loss < currentLoss) {
                currentLoss = loss;
                // replace with new-found priors
                System.arraycopy(trialPriors, 0, currentPriors, 0, currentPriors.length);
                continue;
            }
            
            // Step in the direction of the gradient until there is a loss improvement
            loss = Double.POSITIVE_INFINITY;
            double learnRate = 1.0;
            while (loss > currentLoss) {
                for (int a = 0; a < currentPriors.length; a ++)
                    trialPriors[a] = currentPriors[a] + gradient[a] * learnRate;
                loss = testTrialPriors(trialPriors, ss);
                learnRate *= 0.9;
            }
            if (learnRate < learnRateTolerance) {
                // Converged with small learn rate
                // System.out.println("Exited after " + count + " rounds with loss = " + loss + " learning rate = " + learnRate);
                return trialPriors;
            }
            currentLoss = loss;
            System.arraycopy(trialPriors, 0, currentPriors, 0, currentPriors.length);
        }
        // Reached max iterations
        // System.out.println("Exited after " + count + " rounds with loss = " + currentLoss);
        return currentPriors;
    }

    
    public static void main0(String[] args) {
        java.util.Random rand = new java.util.Random(1);
        Enumerable dom = new Enumerable(2);
        EnumDistrib[] samples = {
            new EnumDistrib(dom, 0.3, 0.7),
            new EnumDistrib(dom, 0.2, 0.8),
            new EnumDistrib(dom, 0.1, 0.9),
            new EnumDistrib(dom, 0.2, 0.8),
            new EnumDistrib(dom, 0.1, 0.9),
            new EnumDistrib(dom, 0.4, 0.6),
        };
        double[] ss = DirichletDistrib.getSufficientStatistic(samples, 2);
        double[] alpha = DirichletDistrib.findPrior(new double[] {.5, 0.5}, ss);
        DirichletDistrib d = new DirichletDistrib(dom, alpha);
        System.out.println(d);

//        int N = 10;
//        int K = 3;
//        Enumerable dom = new Enumerable(K);
//        DirichletDistrib d0 = new DirichletDistrib(dom, 12, 2, 15);
//        System.out.println(d0);
//        int[][] counts = new int[N][K];
//        EnumDistrib[] samples = new EnumDistrib[N];
//        for (int j = 0; j < N; j ++) {
//            EnumDistrib d = (EnumDistrib) d0.sample();
//            samples[j] = d;
//            double p = d0.get(d);
//            System.out.println(j + ": " + d + " p = " + String.format("%4.2f;", p));
//        }
//        double[] ss = DirichletDistrib.getSufficientStatistic(samples, K);
//        double[] alpha = DirichletDistrib.findPrior(new double[] {0.33,0.33,0.33}, ss);
//        d0.setPrior(alpha);
//        System.out.println("Dirichlet Alpha: " + d0);
//        for (int j = 0; j < 30; j ++) {
//            EnumDistrib d = (EnumDistrib) d0.sample();
//            double p = d0.get(d);
//            System.out.println(j + ": " + d + " p = " + String.format("%4.2f;", p));
//        }
        System.out.println("Final loss = " +DirichletDistrib.getTotalLoss(alpha, ss));
        System.out.println("Best loss = " + DirichletDistrib.getTotalLoss(new double[] {1,2}, ss));
        
//        DirichletDistrib d1 = new DirichletDistrib(Enumerable.nacid, 1.0);
//        EnumDistrib[] dd = new EnumDistrib[dna.length];
//        for (int j = 0; j < dna.length; j ++) {
//            dd[j] = new EnumDistrib(Enumerable.nacid);
//            for (Object sym : Enumerable.nacid.getValues()) {
//                int cnt = 0;
//                for (int j = 0; j < dna[j].length(); j ++)
//                    cnt += (dna[j].charAt(j) == (Character)sym ? 1 : 0);
//                dd[j].set(sym, (double) cnt / dna[j].length());
//            }
//            System.out.println(dd[j]);
//        }
//        d1.setPrior(dd);
//        System.out.println(d1);
//        for (int j = 0; j < 10; j ++)
//            System.out.println(d1.sample());
    }

    static String[] dna2 = {
            "AAAAAAAAAACCCCCCCCCCGGGGGGGGGGTTTTTTTTTTAAAAAAACCCCCCCGGGGGGGTTTTTTTAC",
            "TTCGGCACGAGTCTCGGGCGGGAGAAGAAGAAGAATTAGTAAAGTGTGATCATAATGTCTGCTAGCGGCG",
            "GCACCGGAGATGAAGATAAGAAGCCTAATGATCAGATGGTTCATATCAATCTCAAGGTTAAGGGTCAGGA",
            "TGGGAATGAAGTTTTTTTCAGGATCAAACGTAGCACACAGATGCGCAAGCTCATGAATGCTTATTGTGAC",
            "CGGCAGTCAGTGGACATGAACTCAATTGCATTCTTATTTGATGGGCGCAGGCTTAGGGCAGAGCAAACTC",
            "CTGATGAGCTGGAGATGGAGGAGGGTGATGAAATCGATGCAATGCTACATCAAACTGGAGGCAGTTGCTG",
            "CACTTGTTTCTCTAATTTTTAACTTGGTTTATGTTAGTAGATTGTTTAGGGTAATACTTTCAACTCCCTC",
            "ATCTGCTCTAAGATGGGTAAATTTATGAATGTTTAGTTTTCAGTATTAGATGATGACACTACTAAATGGT",
            "TCAATTTTCATGGCATTTGTAAAAGTTTACTCTTAATATGGTTAAAAAGATGATGACACTACTAAATGGT"};
    
    static String[] dna = {
            "ACGTACGTACGTACGTACGTACGTACGTA",
            "AAGTAAGTAAGTAAGTAAGTAAGTAAGTC",
            "AACTAACTAACTAACTAACTAACTAACTG",
            "AACGAACGAACGAACGAACGAACGAACGT",
            "CCGTCCGTCCGTCCGTCCGTCCGTCCGTA"};
    
}