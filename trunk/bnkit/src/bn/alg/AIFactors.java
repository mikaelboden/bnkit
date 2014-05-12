package bn.alg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import bn.BNet;
import bn.BNode;
import bn.Distrib;
import bn.EnumTable;
import bn.EnumVariable;
import bn.FactorTable;
import bn.GaussianDistrib;
import bn.JPT;
import bn.MixtureDistrib;
import bn.Predef;
import bn.QueryTable;
import bn.Variable;
import bn.alg.AITables.AQuery;
import bn.alg.AITables.AResult;
import bn.alg.AITables.ApproxInferRuntimeException;
import bn.alg.AITables.AResult.Evidence;

public class AIFactors implements Inference{
	public BNet bn;
	private double logLikelihood = 1;
	private Random randomGenerator = new Random();
	private int iterations = 50;
	private Variable Counts = Predef.Boolean("Counts");

	/** 
	 * Approximate inference in Bayesian network by Gibbs algorithm (MCMC).
	 * In accordance with the method described in Russell and Norvig, 
	 * Artificial Intelligence: A Modern Approach, 3e, 2009.
	 * 
	 * This particular version of approximate inference addresses each query individually
	 * assigning it a count or sample table depending on whether it is discrete or continuous respectively.
	 * The counts and samples are then converted to distributions and the nodes are updated.
	 * Factor tables are created for each query node and the product is found then the factor 
	 * table is marginalized.
	 * 
	 * Convergence of algorithm is incomplete
	 */
	@Override
	public void instantiate(BNet bn) {
		this.bn = bn;
		this.bn.compile();
	}

	/**
	 * Construct the data structure for the query
	 * X - query variable (non-evidence)
	 * E - evidence variables in bn
	 * Z - all non-evidence variables
	 */
	@SuppressWarnings("rawtypes")
	@Override
	public Query makeQuery(Variable... qvars) {
		List<Variable> X = new ArrayList<Variable>();
		List<Variable> E = new ArrayList<Variable>();
		List<Variable> Z = new ArrayList<Variable>();
		List<Variable> ordered = new ArrayList<Variable>();
		try {
			for (int i = 0; i < qvars.length; i++) {
				X.add(qvars[i]);
			}
			for (BNode node : bn.getOrdered()) {
				Variable var = node.getVariable();
				ordered.add(var);
				if (node.getInstance() != null) {
					E.add(var);
					//need to keep track of ALL non-evidence variables
					//this includes the query variable
				} else {
					Z.add((Variable) var);
				}
			}
		} catch (RuntimeException e) {
			throw new RuntimeException("makeQuery, ApproxInfer didn't work");
		}

		return new AQuery(X, E, Z);

	}

	/**
	 * Perform Approximate inference using Gibbs sampling algorithm
	 */
	@SuppressWarnings("rawtypes")
	@Override
	public AResult infer(Query query) {
		JPT answer = null;
		AQuery q = (AQuery) query;
		//Take a copy of the current state of network
		BNet cbn = bn;
		//First set all non-evidenced nodes including query
		instantiateNet(q.Z, cbn);

		//Create QueryTables for each node where counts will be stored
		List<QueryTable> qTables = new ArrayList<QueryTable>(q.X.size());
		for (Variable nQuery : q.X) {
			QueryTable t = new QueryTable(cbn.getNode(nQuery), cbn);
			qTables.add(t);
		}

		//Iterations of sampling
		int N = iterations;

		//The main loop for the algorithm
		for (int j = 0; j < N; j ++) {
			//Iterate over all non-evidenced nodes
			for (Variable var : q.Z) { 
				//FIXME Consider what order this is happening?
				//Top down? Should it be randomised?
				//Z is an ordered list

				//Get the children of node and node for factors
				List<BNode> mbVar = cbn.getMB(var);
				//Sample from the mb distribution
				Object result = bn.getMBProb(mbVar, bn.getNode(var), bn);
				if (result != null) {
					cbn.getNode(var).setInstance(result);
				}
			}
			//Update Query count
			for (QueryTable qTab : qTables) {
				List<EnumVariable> parList = null;
				if (qTab.getSampleTable() != null) {
					qTab.sampleInstance(cbn);
				} else {
					qTab.countInstance(cbn);
				}
			}
		}

		//Reset all unevidenced nodes in network
		for (BNode node : cbn.getNodes()) {
			if (q.Z.contains(node.getVariable())){
				node.resetInstance();
			}
		}
		
		//Maximize values in each table (obtain distributions)
		for (QueryTable qTab : qTables) {
			if (qTab.hasSample()) {
				qTab.getSampleTable().maximizeInstance(qTab);
			} else if (qTab.hasCount()) {
				qTab.getCountTable().maximizeInstance(qTab);
			}
		}
		
		//Create factor tables for each query		
		List<FactorTable> factors = new ArrayList<FactorTable>(qTables.size());
		for (QueryTable qTab : qTables) {
			BNode cQuery = qTab.getQuery();
			FactorTable ft = cQuery.makeFactor(cbn);
			factors.add(ft);
		}

		FactorTable result = null;

		//Get the product of factor tables to record influence of other nodes
		result = factors.get(0);
		for (int i = 1; i < factors.size(); i++) {
			result = FactorTable.product(result, factors.get(i));
		}

		//Find parents that need to be summed out during marginalization
		//Recall - cannot marginalize nonEnum variables
		List<EnumVariable> enum1 = result.getEnumVariables();
		List<EnumVariable> evars = new ArrayList<EnumVariable>();
		for (EnumVariable v : enum1) {
			if (!q.X.contains(v)) {
				evars.add(v);
			}
		}
		
		result = result.marginalize(evars);	
		
		//FIXME RESET TABLES?
		
		return extractResult(result, q.X);

		//Convergence of algorithm is incomplete
		//	    	logLikelihood += 1;


	}
	
	private AResult extractResult(FactorTable result, List<Variable> query) {
        JPT answer = null;
        List<EnumVariable> f_parents = result.getEnumVariables();
        List<EnumVariable> q_parents = new ArrayList<>();
        for (Variable q_par : query) {
            try {
                q_parents.add((EnumVariable)q_par);
            } catch (ClassCastException e) {
                ; // ignore non-enumerable variables since they will not be parents in the resulting JPT
            }
        }
        int[] map2q = new int[q_parents.size()];
        for (int jf = 0; jf < map2q.length; jf ++) {
            map2q[jf] = -1;
            for (int jq = 0; jq < map2q.length; jq ++) {
                if (f_parents.get(jf).getName().equals(q_parents.get(jq).getName()))  {
                    map2q[jf] = jq;
                    break;
                }
            }
        }
        for (int j = 0; j < map2q.length; j ++) {
            if (map2q[j] == -1)
                throw new ApproxInferRuntimeException("Invalid inference result");
        }
        EnumTable<Double> et = new EnumTable<Double>(q_parents);
        Map<Variable, EnumTable<Distrib>> nonEnumTables = null;
        Map<Variable, Distrib> nonEnumDistribs = null;
        if (result.hasNonEnumVariables()) {
            if (result.isAtomic()) {
                nonEnumDistribs = new HashMap<>();
                for (Variable nonenum : result.getNonEnumVariables())
                    nonEnumDistribs.put(nonenum, result.getDistrib(-1, nonenum));
            } else {
                nonEnumTables = new HashMap<>();
                for (Variable nonenum : result.getNonEnumVariables())
                    nonEnumTables.put(nonenum, new EnumTable<Distrib>(q_parents));
            }
        }
        for (Map.Entry<Integer, Double> entry : result.getMapEntries()) {
            Object[] fkey = result.getKey(entry.getKey().intValue());
            Object[] qkey = new Object[fkey.length];
            for (int j = 0; j < fkey.length; j ++)
                qkey[map2q[j]] = fkey[j];
            et.setValue(qkey, entry.getValue());
            if (nonEnumTables != null) {
                for (Variable nonenum : result.getNonEnumVariables()) {
                    Distrib d = result.getDistrib(fkey, nonenum);
                    EnumTable<Distrib> table = nonEnumTables.get(nonenum);
                    table.setValue(qkey, d);
                }
            }
        }

        if (nonEnumTables != null) {
            answer = new JPT(et);
            return new AResult(result, answer, nonEnumTables);
        }
        if (nonEnumDistribs != null)
            return new AResult(result, nonEnumDistribs);
        answer = new JPT(et);
        return new AResult(result, answer);
    }

	public class AQuery implements Query {

		final List<Variable> X;
		final List<Variable> E;
		final List<Variable> Z;

		AQuery(List<Variable> X, List<Variable> E, List<Variable> Z) {
			this.X = X;
			this.E = E;
			this.Z = Z;
		}
	}

	 public class AResult implements QueryResult {

	        final FactorTable ft;
	        final private JPT jpt;
	        final private Map<Variable, EnumTable<Distrib>> nonEnumTables;
	        final private Map<Variable, Distrib> nonEnumDistribs;
	        
	        public AResult(FactorTable ft, JPT jpt) {
	            this.ft = ft;
	            this.jpt = jpt;
	            this.nonEnumTables = null;
	            this.nonEnumDistribs = null;
	        }

	        public AResult(FactorTable ft, JPT jpt, Map<Variable, EnumTable<Distrib>> nonEnum) {
	            this.ft = ft;
	            this.jpt = jpt;
	            this.nonEnumTables = nonEnum;
	            this.nonEnumDistribs = null;
	        }
	        
	        public AResult(FactorTable ft, Map<Variable, Distrib> nonEnum) {
	            this.ft = ft;
	            this.jpt = null;
	            this.nonEnumTables = null;
	            this.nonEnumDistribs = nonEnum;
	        }
	        
	        @Override
	        public JPT getJPT() {
	            return this.jpt;
	        }
	        
	        public Map<Variable, EnumTable<Distrib>> getNonEnum() {
	            return this.nonEnumTables;
	        }
	        
	        public Map<Variable, Distrib> getNonEnumDistrib() {
	        	return this.nonEnumDistribs;
	        }
	        
	        /**
	         * Method to retrieve a single distribution with all other variables in original query unspecified.
	         * @param query the query variable
	         * @return 
	         */
	        public Distrib getDistrib(Variable query) {
	            return getDistrib(query, null);
	        }

	        /**
	         * Method to retrieve a single distribution GIVEN some optional evidence.
	         * @param query the query variable
	         * @return 
	         */
	        public Distrib getDistrib(Variable query, Evidence... evid) {
	            
	            return null;
	        }
	        
	        public class Evidence {
	            public Variable var; Object val;
	            public Evidence(Variable var, Object val) { this.var = var; this.val = val; }
	        }
	    }

    public BNet instantiateNet(List<Variable> uninstantiated, BNet cbn) {
    	//Occurs only once during inference
    	//Should be done randomly**
    	for (Variable var : uninstantiated) {
    		String pre = var.getPredef();
    		//getParams() did not return suitable results for boolean nodes
    		//Set manually
    		if (pre.equals("Boolean")){
    			Object[] params = {true, false};
    			//Generate a pseudo random number to select parameter
    			int index = randomGenerator.nextInt(params.length);
    			//Randomly set the instance for the node
    			cbn.getNode(var).setInstance(params[index]);
    		} else if (pre.equals("Real")){
    			// Ultimately want to sample from one of the possible distributions
    			// FIXME - better way to do this sampling? Something without odd casts
    			Object[] nodeDistribs = cbn.getNode(var).getTable().getValues().toArray();
    			int index = randomGenerator.nextInt(nodeDistribs.length);
    			//Get individual mu and sigma from random distribution
    			String[] values = nodeDistribs[index].toString().split(";");
    			//Create distribution to sample from
    			Distrib d = new GaussianDistrib(Double.parseDouble(values[0]), Double.parseDouble(values[1]));
    			//If this distribution is randomly generated it will not be a very accurate value here
    			cbn.getNode(var).setInstance(d.sample());
    		} else {
    			String parm = var.getParams();
    			String[] params;
    			if (parm != null) {
    				params = parm.split(";");
    				//Generate a pseudo random number to select parameter
    				int index = randomGenerator.nextInt(params.length);
    				//Randomly set the instance for the node
    				cbn.getNode(var).setInstance(params[index]); 
    			} else {
    				throw new ApproxInferRuntimeException("Node must contain parameters");
    			}
    		}
    	}
    	return cbn;
    }
    
	/**
	 * Get the number of iterations sampling will complete
	 * @return iterations
	 */
	public int getIterations() {
		return iterations;
	}

	/**
	 * Set the number of iterations sampling will complete
	 * @return iterations
	 */
	public void setIterations(int iter) {
		iterations = iter;
	}
	

	public class ApproxInferRuntimeException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public ApproxInferRuntimeException(String message) {
			super(message);
		}
	}

}