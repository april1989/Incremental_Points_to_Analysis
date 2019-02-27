/*******************************************************************************
 * Copyright (C) 2017 Bozhen Liu, Jeff Huang.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Bozhen Liu, Jeff Huang - initial API and implementation
 ******************************************************************************/
package edu.tamu.wala.increpta.parallel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.ibm.wala.util.intset.IntSetAction;
//import com.ibm.wala.util.intset.IntSetUtil;
import com.ibm.wala.util.intset.MutableIntSet;
//import com.ibm.wala.util.intset.MutableSharedBitVectorIntSet;
//import com.ibm.wala.util.intset.MutableSharedBitVectorIntSetFactory;

import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPointsToSetVariable;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationCallGraphBuilder.IPAFilterOperator;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationGraph;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationSystem;
import edu.tamu.wala.increpta.operators.IPAAbstractOperator;
import edu.tamu.wala.increpta.operators.IPAAbstractStatement;
import edu.tamu.wala.increpta.operators.IPAAssignOperator;
import edu.tamu.wala.increpta.operators.IPAUnaryStatement;
import edu.tamu.wala.increpta.scc.SCCVariable;
import edu.tamu.wala.increpta.util.DeletionUtil;
import edu.tamu.wala.increpta.util.IPAAbstractFixedPointSolver;
import edu.tamu.wala.increpta.util.intset.IPAIntSetUtil;
import edu.tamu.wala.increpta.util.intset.IPAMutableSharedBitVectorIntSet;
import edu.tamu.wala.increpta.util.intset.IPAMutableSharedBitVectorIntSetFactory;

public class ThreadHub {

	public static ExecutorService threadrouter;
	private static int nrOfResults = 0;
	private static int nrOfWorks;
	private static boolean finished = false;

	public static HashSet<IPAPointsToSetVariable> processed = new HashSet<>();

	public ThreadHub(int nrOfWorkers) {
		threadrouter = Executors.newWorkStealingPool(nrOfWorkers);
	}

	public ExecutorService getThreadRouter(){
		return threadrouter;
	}

	/**
	 *
	 * @param targets <- getchanges()
	 * @param firstusers
	 * @param system
	 * @throws InterruptedException
	 * @throws ExecutionException
	 */
	public void initialRRTasks(MutableIntSet targets, ArrayList<IPAPointsToSetVariable> firstusers,
			IPAPropagationSystem system) throws InterruptedException, ExecutionException{
		//    System.err.println("RR is called. ");
		ArrayList<Callable<ResultFromRR>> tasks = distributeRRTasks(targets, firstusers, system);
		ArrayList<Future<ResultFromRR>> results = (ArrayList<Future<ResultFromRR>>) threadrouter.invokeAll(tasks);
		continueRRTasks(results, system);
	}

	private void continueRRTasks(ArrayList<Future<ResultFromRR>> results, IPAPropagationSystem system) throws InterruptedException, ExecutionException {
		ArrayList<Callable<ResultFromRR>> tasks = new ArrayList<>();
		for (Future<ResultFromRR> future : results) {
			nrOfResults ++;
			ResultFromRR result = future.get();
			IPAMutableSharedBitVectorIntSet change = result.getUser().getChange();
			ArrayList<IPAPointsToSetVariable> nexts = result.getCheckNext();
			if(!nexts.isEmpty() && change.size() > 0){
				Iterator<IPAPointsToSetVariable> iterator = nexts.iterator();
				while(iterator.hasNext()){
					IPAPointsToSetVariable next = iterator.next();
					if(next.getValue() != null){
						tasks.addAll(distributeRRTasks(change, nexts, system));
					}
				}
			}
		}
		if(doWeTerminate()){
			return;
		}
		ArrayList<Future<ResultFromRR>> next_results = (ArrayList<Future<ResultFromRR>>) threadrouter.invokeAll(tasks);
		continueRRTasks(next_results, system);
	}

	private static ArrayList<Callable<ResultFromRR>> distributeRRTasks(final MutableIntSet targets, ArrayList<IPAPointsToSetVariable> firstusers,
			final IPAPropagationSystem system) {
		ArrayList<Callable<ResultFromRR>> tasks = new ArrayList<>();
		Iterator<IPAPointsToSetVariable> users = firstusers.iterator();
		while(users.hasNext()){
			final IPAPointsToSetVariable user = users.next();
			if(processed.contains(user))
				continue;
			processed.add(user);
			nrOfWorks++;
			tasks.add(new Callable<ResultFromRR>() {
				@Override
				public ResultFromRR call() throws Exception {
					TaskForRR taskForRR = new TaskForRR(user, targets, system);
					return processRRTask(taskForRR);
				}
			});
		}
		return tasks;
	}


	public void initialSpecialTasks(ArrayList<IPAPointsToSetVariable> lhss, MutableIntSet targets,  boolean isAddition,
			IPAPropagationSystem system) throws InterruptedException, ExecutionException{
		//    System.err.println("Speical is called. ");
		ArrayList<Callable<ResultFromSpecial>> tasks = distributeSpecialTasks(targets, lhss, isAddition, system);
		ArrayList<Future<ResultFromSpecial>> results = (ArrayList<Future<ResultFromSpecial>>) threadrouter.invokeAll(tasks);
		continueSpecialTasks(results, isAddition, system);
	}

	private void continueSpecialTasks(ArrayList<Future<ResultFromSpecial>> results, boolean isAddition,
			IPAPropagationSystem system) throws InterruptedException, ExecutionException {
		ArrayList<Callable<ResultFromSpecial>> tasks = new ArrayList<>();
		for (Future<ResultFromSpecial> future : results) {
			nrOfResults++;
			ResultFromSpecial result = future.get();
			IPAMutableSharedBitVectorIntSet change = result.getUser().getChange();
			ArrayList<IPAPointsToSetVariable> nexts = result.getCheckNext();
			if(!nexts.isEmpty() && change.size() > 0){
				Iterator<IPAPointsToSetVariable> iterator = nexts.iterator();
				while(iterator.hasNext()){
					IPAPointsToSetVariable next = iterator.next();
					if(next.getValue() != null){
						tasks.addAll(distributeSpecialTasks(change, nexts, isAddition, system));
					}
				}
			}
		}

		if(doWeTerminate()){
			return;
		}
		ArrayList<Future<ResultFromSpecial>> next_results = (ArrayList<Future<ResultFromSpecial>>) threadrouter.invokeAll(tasks);
		continueSpecialTasks(next_results, isAddition, system);
	}

	private static ArrayList<Callable<ResultFromSpecial>> distributeSpecialTasks(final MutableIntSet targets, ArrayList<IPAPointsToSetVariable> lhss,
			final boolean isAddition, final IPAPropagationSystem system) {
		ArrayList<Callable<ResultFromSpecial>> tasks = new ArrayList<>();
		Iterator<IPAPointsToSetVariable> users = lhss.iterator();
		while(users.hasNext()){
			final IPAPointsToSetVariable user = users.next();
			if(processed.contains(user))
				continue;
			processed.add(user);
			nrOfWorks++;
			tasks.add(new Callable<ResultFromSpecial>() {
				@Override
				public ResultFromSpecial call() throws Exception {
					TaskForSpecial job = new TaskForSpecial(user, targets, isAddition, system);
					if(isAddition)
						return processSpecialWorkAddition(job);
					else
						return processSpecialWorkDeletion(job);
				}
			});
		}
		return tasks;
	}

	private static boolean doWeTerminate() {
		// if all jobs complete
		if(nrOfResults == nrOfWorks){
			//clear this round
			nrOfWorks = 0;
			nrOfResults = 0;
			finished  = true;
			processed.clear();
			return true;
		}
		return false;
	}

	protected static IPAMutableSharedBitVectorIntSet computeRemaining(MutableIntSet delSet, IPAPointsToSetVariable L, IPAPropagationGraph flowGraph){
		//recompute L
		final IPAMutableSharedBitVectorIntSet remaining = new IPAMutableSharedBitVectorIntSetFactory().makeCopy(delSet);
		for (IPAPointsToSetVariable pv : flowGraph.getPointsToSetVariablesThatDefImplicitly(L)) {
			if(remaining.isEmpty())
				break;
			if(pv instanceof SCCVariable){
				((SCCVariable) pv).ifOthersCanProvide(L, remaining, delSet, flowGraph);
			}else if(pv.getValue() != null){
				if(remaining.size() == 0)
					break;
				MutableIntSet set = pv.getValue();
				if(set != null){
					MutableIntSet set1;
					synchronized (pv) {
						set1 = IPAIntSetUtil.makeMutableCopy(set);
					}
					DeletionUtil.removeSome(remaining, set1);
				}else
					continue;

//				IntSetAction action = new IntSetAction() {
//					@Override
//					public void act(int i) {
//						if(remaining.isEmpty())
//							return;
//						if(delSet.contains(i)){
//							remaining.remove(i);
//						}
//					}
//				};
			}
		}
		return remaining;
	}


	private static void classifyPointsToConstraints(IPAPointsToSetVariable L,
			 IPAPropagationSystem system, ArrayList<IPAPointsToSetVariable> next) throws InterruptedException{
		for (Iterator it = system.getPropagationGraph().getStatementsThatUse(L); it.hasNext();) {
			IPAAbstractStatement s = (IPAAbstractStatement) it.next();
			IPAAbstractOperator op = s.getOperator();
			if(op instanceof IPAAssignOperator){// || op instanceof IPAFilterOperator
				if(system.checkSelfRecursive(s))
					continue;
				IPAPointsToSetVariable pv = (IPAPointsToSetVariable) s.getLHS();
				if(pv.getValue() != null)
					next.add(pv);
			}
			else{
					system.addToWorkListSync(s);
			}
		}
	}


	private static ResultFromRR processRRTask(TaskForRR work) throws InterruptedException {
		final IPAPointsToSetVariable user = work.getUser();
		final MutableIntSet targets = work.getTargets();
		final IPAPropagationSystem system = work.getPropagationSystem();
		ArrayList<IPAPointsToSetVariable> next = new ArrayList<>();
		//check
		IPAMutableSharedBitVectorIntSet remaining = computeRemaining(targets, user, system.getPropagationGraph());
		if(system.isTransitiveRoot(user.getPointerKey()))
			return new ResultFromRR(user, next, remaining);

		//check if changed
		if(!remaining.isEmpty()){
			synchronized (user) {
				remaining = DeletionUtil.removeSome(user, remaining);//?sync
			}
			if(user.getChange().size() > 0){
				IPAAbstractFixedPointSolver.addToChanges(user);
				//future
				classifyPointsToConstraints(user, system, next);
			}
		}else{//all included, early return
		}

		return new ResultFromRR(user, next, remaining);
	}


	private static ResultFromSpecial processSpecialWorkAddition(TaskForSpecial work) throws InterruptedException {
		final IPAPointsToSetVariable user = work.getUser();
		final MutableIntSet target = work.getTargets();
		final IPAPropagationSystem system = work.getPropagationSystem();
		ArrayList<IPAPointsToSetVariable> next = new ArrayList<>();
		if(user.getValue() == null)
			return new ResultFromSpecial(user, next, (IPAMutableSharedBitVectorIntSet) target, work.getIsAdd());
		
		IPAMutableSharedBitVectorIntSet remaining = new IPAMutableSharedBitVectorIntSetFactory().makeCopy(target);
		MutableIntSet copy = null;
		synchronized (user) {
			copy = IPAIntSetUtil.makeMutableCopy(user.getValue());
		}
		DeletionUtil.removeSome((IPAMutableSharedBitVectorIntSet) remaining, copy);

		if(!remaining.isEmpty()){
			boolean change = false;
			synchronized (user) {
				change = user.addAll(remaining);
				user.setChange(remaining);
			}
			if(!change)//not changed
				return new ResultFromSpecial(user, next, remaining, work.getIsAdd());

			//when changed
			IPAAbstractFixedPointSolver.addToChanges(user);
			//further check
			for (Iterator it = system.getPropagationGraph().getStatementsThatUse(user); it.hasNext();) {
				IPAAbstractStatement s = (IPAAbstractStatement) it.next();
				IPAAbstractOperator op = s.getOperator();
				if(op instanceof IPAAssignOperator){//|| op instanceof IPAFilterOperator
					IPAPointsToSetVariable pv = (IPAPointsToSetVariable) s.getLHS();
					if(pv.getValue() != null){
						next.add(pv);
					}
				}
				else{
						system.addToWorkListSync(s);
				}
			}
		}else{
		}
		return new ResultFromSpecial(user, next, remaining, work.getIsAdd());
	}

	private static ResultFromSpecial processSpecialWorkDeletion(TaskForSpecial work) throws InterruptedException {
		final IPAPointsToSetVariable user = work.getUser();
		final MutableIntSet targets = work.getTargets();
		final IPAPropagationSystem system = work.getPropagationSystem();
		ArrayList<IPAPointsToSetVariable> next = new ArrayList<>();

		IPAMutableSharedBitVectorIntSet remaining = computeRemaining(targets, user, system.getPropagationGraph());
		if(system.isTransitiveRoot(user.getPointerKey()))
			return new ResultFromSpecial(user, next, remaining, work.getIsAdd());

		if(!remaining.isEmpty()){
			synchronized (user) {
				remaining = DeletionUtil.removeSome(user, remaining);//?sync
			}
			if(user.getChange().size() > 0){
				IPAAbstractFixedPointSolver.addToChanges(user);
				//future
				classifyPointsToConstraints(user, system, next);
			}
		}else{//all included, early return
		}

		return new ResultFromSpecial(user, next, remaining, work.getIsAdd());
	}

}
