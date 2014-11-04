package edu.illinois.cs.cogcomp.sl.learner.l2_loss_svm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.illinois.cs.cogcomp.core.datastructures.Pair;
import edu.illinois.cs.cogcomp.sl.core.AbstractFeatureGenerator;
import edu.illinois.cs.cogcomp.sl.core.AbstractInferenceSolver;
import edu.illinois.cs.cogcomp.sl.core.IInstance;
import edu.illinois.cs.cogcomp.sl.core.IStructure;
import edu.illinois.cs.cogcomp.sl.core.SLParameters;
import edu.illinois.cs.cogcomp.sl.core.SLProblem;
import edu.illinois.cs.cogcomp.sl.util.WeightVector;

/**
 * A parallel version of L2LossSSVMDCDSolver 
 * When updating working set, the algorithm solves augmented inferences 
 * over all the training samples parallely. The parallel version is especially 
 * useful when the inference takes large amount of time.  
 * <p>
 * Please see the following papers for deatils:
 * Ming-Wei Chang and Wen-tau Yih, Dual Coordinate Descent Algorithms for 
 * Efficient Large Margin Structured Prediction, TACL, 2013.
 * 
 * Ming-Wei Chang, Vivek Srikumar, Dan Goldwasser and Dan Roth. Structured 
 * output learning with indirect supervision. ICML, 2010.
 * 
 * @author Ming-Wei Chang
 * 
 */

public class L2LossSSVMParalleDCDSolver extends L2LossSSVMDCDSolver {
	static Logger logger = LoggerFactory.getLogger(L2LossSSVMParalleDCDSolver.class);

	Random rnd = new Random(0);
	AbstractInferenceSolver[] infSolvers;
	int numThreads = 0;

	public L2LossSSVMParalleDCDSolver(AbstractInferenceSolver infSolver, AbstractFeatureGenerator fg, int numThreads) {
		super(infSolver, fg);
		infSolvers = new AbstractInferenceSolver[numThreads];
		for(int i=0; i<numThreads; i++){
			infSolvers[i] = (AbstractInferenceSolver) infSolver.clone();
		}
		this.numThreads = numThreads;
	}

	public L2LossSSVMParalleDCDSolver(AbstractInferenceSolver[] infSolvers, AbstractFeatureGenerator fg, int numThreads) {
		super(infSolvers[0], fg);
		this.infSolvers = infSolvers;
		this.numThreads = numThreads;
	}

	/**
	 * The parallel version of
	 * {@link L2LossSSVMDCDSolver#train(SLProblem, SLParameters)}
	 * <p>
	 * 
	 * 
	 * @param sp
	 * @param parameters
	 * @return
	 * @throws Exception
	 */

	@Override
	public WeightVector train(final SLProblem sp, SLParameters parameters) throws Exception{ 
		WeightVector wv = new WeightVector(10000);

		if (parameters.TRAINMINI && 5 * parameters.TRAINMINI_SIZE < sp.size()) {
			int trainMiniSize = parameters.TRAINMINI_SIZE;
			logger.info("Train a mini sp to speed up! size = " + trainMiniSize);
			SLProblem minisp = new SLProblem();
			minisp.instanceList = new ArrayList<IInstance>();
			minisp.goldStructureList = new ArrayList<IStructure>();
			ArrayList<Integer> indexList = new ArrayList<Integer>();
			for (int i = 0; i < sp.size(); i++)
				indexList.add(i);
			Collections.shuffle(indexList, new Random(0));

			for (int i = 0; i < trainMiniSize; i++) {
				int idx = indexList.get(i);
				minisp.instanceList.add(sp.instanceList.get(idx));
				minisp.goldStructureList.add(sp.goldStructureList.get(idx));
			}

			wv = trainSSVM(wv, infSolvers,
					minisp, parameters);
		}

		return trainSSVM(wv, infSolvers, sp,
				parameters);
	}

	private class StructInferenceHandler extends Thread {
		public int numOfNewStructures = 0;
		private AbstractInferenceSolver infSolver;
		private List<StructuredInstanceWithAlphas> alphaInsList;
		private WeightVector wv;
		private SLParameters parameters;

		public StructInferenceHandler(
				AbstractInferenceSolver infSolver,
				List<StructuredInstanceWithAlphas> subset, WeightVector wv,
				SLParameters parameters) {
			this.infSolver = infSolver;
			this.alphaInsList = subset;
			this.wv = wv;
			this.parameters = parameters;
		}

		@Override
		public void run() {
			for (StructuredInstanceWithAlphas ins : alphaInsList) {
				try {
					numOfNewStructures += ins.updateRepresentationCollection(wv, infSolver, parameters);
				} catch (Exception e) {
					e.printStackTrace();
					System.exit(1);
				}
			}
			logger.trace("Thread: structure udpates = "	+ numOfNewStructures);
		}
	}
	
	private WeightVector trainSSVM(
			WeightVector wv, final AbstractInferenceSolver[] infSolvers,
			SLProblem sp, SLParameters parameters) throws Exception {		
		StructuredInstanceWithAlphas[] alphaInsList = initArrayOfInstances(sp,
				parameters.C_FOR_STRUCTURE, sp.size());

		// start training
		boolean finished = false;
		boolean resolved = false;

		resolved = false;
		finished = false;
		for (int iter = 0; iter < parameters.MAX_NUM_ITER; iter++) {
			// update structured labeled data
			int numOfNewStructures = updateStructuresUsingMultiThreads(
					alphaInsList, wv, parameters);

			// no more update is necessary, exit the internal loop
			if (iter != 0 && numOfNewStructures == 0) {
				if (finished == false)
					resolved = true;
				else {
					logger.info("Met the stopping condition; Exit Inner loop");
					break;
				}
			}

			// update w and alphas using working set.
			Pair<Float, Boolean> res = updateWvWithWorkingSet(
					alphaInsList, wv, parameters);
			if (resolved) {
				finished = true;
				logger.info("(Resolved) Met the stopping condition; Exit Inner loop");
				break;

			} else {
				finished = res.getSecond();
			}
			
			if(iter % parameters.PROGRESS_REPORT_ITER == 0) {
				logger.info("Iteration: " + iter
						+ ": Add " + numOfNewStructures
						+ "candidate structures into the working set.");
				logger.info("negative dual obj = " + res.getFirst());
				if(f!=null)
						f.run(wv, (AbstractInferenceSolver)infSolvers[0].clone());
			}
			
			// remove unused alphas from working set
			if (parameters.CLEAN_CACHE && iter % parameters.CLEAN_CACHE_ITER == 0) {
				for (int i = 0; i < sp.size(); i++) {
					alphaInsList[i].cleanCache(wv);
				}
				logger.trace("Cleaning cache....");
			}

			iter++;
		}
		return wv;
	}


	private int updateStructuresUsingMultiThreads (
			StructuredInstanceWithAlphas[] alphaInsList, WeightVector wv, SLParameters parameters)
					throws InterruptedException {

		// initialize thread
		int NumOfThreads = infSolvers.length;
		StructInferenceHandler[] infRunnerList = new StructInferenceHandler[NumOfThreads];
		for (int i = 0; i < NumOfThreads; i++) {
			List<StructuredInstanceWithAlphas> subset = new ArrayList<StructuredInstanceWithAlphas>();
			for (int j = 0; j < alphaInsList.length; j++) {
				if (j % NumOfThreads == i)
					subset.add(alphaInsList[j]);
			}
			infRunnerList[i] = new StructInferenceHandler(
					infSolvers[i], subset, wv, parameters);
		}

		// run the thread
		for (int i = 0; i < NumOfThreads; i++) {
			infRunnerList[i].start();
		}

		// wait until all of them are finished
		for (int i = 0; i < NumOfThreads; i++) {
			infRunnerList[i].join();
		}

		// collect results
		int numOfNewStructures = 0;
		for (int i = 0; i < NumOfThreads; i++) {
			numOfNewStructures += infRunnerList[i].numOfNewStructures;
		}

		return numOfNewStructures;
	}
}
