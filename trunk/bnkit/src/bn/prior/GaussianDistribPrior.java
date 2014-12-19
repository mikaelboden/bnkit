package bn.prior;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Random;

import dat.Enumerable;

import bn.Distrib;
import bn.prob.EnumDistrib;
import bn.prob.GaussianDistrib;

public class GaussianDistribPrior extends GaussianDistrib implements Prior, Serializable{
    
	// currently, Gaussian is only used as conjudge prior for GaussianDistrib
	private GaussianDistrib likelihoodDistrib;
	private double oldMean;
	private double oldVariance;
	
    public GaussianDistribPrior(double mean, double variance) {
    	super(mean, variance);
    	likelihoodDistrib = null;
    	oldMean = mean;
    	oldVariance = variance;
    }
    
    /**
     * MAP algorithm for Gaussian Distribution with known variance
     */
	@Override
	public void learn(Object[] data, double[] prob) {
		Double[] trainingData = (Double[]) data;
		double sum = 0.0;
		double posteriorMean;
		double posteriorVariance;
		
		if(likelihoodDistrib == null) {
			System.err.println("likelihood distribution should be specificed");
			return;
		}
		
		/**
		 * get the sum of all training Data
		 */
		for(Double point: trainingData) {
			sum += point.doubleValue();
		}
		/**
		 * formula for MAP
		 */
		posteriorMean = (mu / sigmaSquared + sum / likelihoodDistrib.getVariance()) 
				/ ( 1 / sigmaSquared + trainingData.length / likelihoodDistrib.getVariance());
		posteriorVariance = 1 / ( 1 / sigmaSquared + trainingData.length / likelihoodDistrib.getVariance());
		// set parameters
		setMean(posteriorMean);
		setVariance(posteriorVariance);
	}


	@Override
	public void setLikelihoodDistrib(Distrib distrib) {
		try {
			likelihoodDistrib = (GaussianDistrib) distrib;
		} catch(ClassCastException e) {
			System.out.println("the likelihood for Gaussian prior should be Guassian distribution");
		}
		
	}

	@Override
	public Distrib getBayesDistrib() {
		// the mode for Gaussian is mu
		likelihoodDistrib.mu = getMean();
		return likelihoodDistrib;
	}

	@Override
	public void resetParameters() {
		setMean(oldMean);
		setVariance(oldVariance);
		
	}

	@Override
	public void learnPrior(Object[] data, double[] prob) {
		// TODO Auto-generated method stub
		
	}
	
	public static GaussianDistribPrior getUniformDistrib() {
		return new GaussianDistribPrior(1.0, Double.POSITIVE_INFINITY);
	}

}
