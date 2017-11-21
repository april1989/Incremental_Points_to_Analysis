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
import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.ibm.wala.fixpoint.IVariable;
import com.ibm.wala.ipa.callgraph.propagation.PointsToSetVariable;
import com.ibm.wala.util.intset.IntSetAction;
import com.ibm.wala.util.intset.IntSetUtil;
import com.ibm.wala.util.intset.MutableIntSet;
import com.ibm.wala.util.intset.MutableSharedBitVectorIntSet;
import com.ibm.wala.util.intset.MutableSharedBitVectorIntSetFactory;

import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationSystem;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationCallGraphBuilder.IPAFilterOperator;
import edu.tamu.wala.increpta.operators.IPAAbstractOperator;
import edu.tamu.wala.increpta.operators.IPAAbstractStatement;
import edu.tamu.wala.increpta.operators.IPAAssignOperator;
import edu.tamu.wala.increpta.operators.IPAUnaryStatement;
import edu.tamu.wala.increpta.util.DeletionUtil;
import edu.tamu.wala.increpta.util.IPAAbstractFixedPointSolver;

public class ThreadHub {

  public ExecutorService threadrouter;
  private static int nrOfResults = 0;
  private static int nrOfWorks;
  private static boolean finished = false;

  public ThreadHub(int nrOfWorkers) {
    threadrouter = Executors.newWorkStealingPool(nrOfWorkers);
  }

  public ExecutorService getThreadRouter(){
    return threadrouter;
  }

  public void initialRRTasks(MutableIntSet targets, ArrayList<PointsToSetVariable> firstusers,
      IPAPropagationSystem system) throws InterruptedException, ExecutionException{
//    System.err.println("RR is called. ");
    ArrayList<Callable<ResultFromRR>> tasks = distributeRRTasks(targets, firstusers, system);
    ArrayList<Future<ResultFromRR>> results = (ArrayList<Future<ResultFromRR>>) threadrouter.invokeAll(tasks);
    continueRRTasks(results,targets, system);
  }

  private void continueRRTasks(ArrayList<Future<ResultFromRR>> results,MutableIntSet targets, IPAPropagationSystem system) throws InterruptedException, ExecutionException {
    ArrayList<com.ibm.wala.ipa.callgraph.propagation.PointsToSetVariable> firstusers = new ArrayList<>();
    for (Future<ResultFromRR> future : results) {
      nrOfResults ++;
      ResultFromRR result = future.get();
      MutableIntSet newtarget = result.getNewTargets();
      ArrayList<com.ibm.wala.ipa.callgraph.propagation.PointsToSetVariable> nexts = result.getCheckNext();
      if(nexts != null){
        if(!nexts.isEmpty() && newtarget.size() > 0){
          Iterator<PointsToSetVariable> iterator = nexts.iterator();
          while(iterator.hasNext()){
            PointsToSetVariable next = iterator.next();
            if(next.getValue() != null){
              firstusers.add(next);
            }
          }
        }
      }
      doWeTerminate();
    }
    if(firstusers.size() > 0)
      initialRRTasks(targets, firstusers, system);
    doWeTerminate();
  }

  private static ArrayList<Callable<ResultFromRR>> distributeRRTasks(final MutableIntSet targets, ArrayList<PointsToSetVariable> firstusers,
      final IPAPropagationSystem system) {
    ArrayList<Callable<ResultFromRR>> tasks = new ArrayList<>();
    Iterator<PointsToSetVariable> users = firstusers.iterator();
    while(users.hasNext()){
      final PointsToSetVariable user = users.next();
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


  public void initialSpecialTasks(ArrayList<PointsToSetVariable> lhss, MutableIntSet targets,  boolean isAddition,
      IPAPropagationSystem system) throws InterruptedException, ExecutionException{
//    System.err.println("Speical is called. ");
    ArrayList<Callable<ResultFromSpecial>> tasks = distributeSpecialTasks(targets, lhss, isAddition, system);
    ArrayList<Future<ResultFromSpecial>> results = (ArrayList<Future<ResultFromSpecial>>) threadrouter.invokeAll(tasks);
    continueSpecialTasks(results,targets, isAddition, system);
  }

  private void continueSpecialTasks(ArrayList<Future<ResultFromSpecial>> results, MutableIntSet targets, boolean isAddition,
      IPAPropagationSystem system) throws InterruptedException, ExecutionException {
    ArrayList<PointsToSetVariable> firstusers = new ArrayList<>();
    for (Future<ResultFromSpecial> future : results) {
      nrOfResults++;
      ResultFromSpecial result = future.get();
      MutableIntSet newtarget = result.getNewTargets();
      ArrayList<PointsToSetVariable> nexts = result.getCheckNext();
      if(nexts != null){
        if(!nexts.isEmpty() && newtarget.size() > 0){
          Iterator<PointsToSetVariable> iterator = nexts.iterator();
          while(iterator.hasNext()){
            PointsToSetVariable next = iterator.next();
            if(next.getValue() != null){
              firstusers.add(next);
            }
          }
        }
      }
      doWeTerminate();
    }
    if(firstusers.size() > 0)
      initialSpecialTasks(firstusers, targets, isAddition, system);
    doWeTerminate();
  }

  private static ArrayList<Callable<ResultFromSpecial>> distributeSpecialTasks(final MutableIntSet targets, ArrayList<PointsToSetVariable> lhss,
      final boolean isAddition, final IPAPropagationSystem system) {
    ArrayList<Callable<ResultFromSpecial>> tasks = new ArrayList<>();
    Iterator<PointsToSetVariable> users = lhss.iterator();
    while(users.hasNext()){
      final PointsToSetVariable user = users.next();
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

  private static void doWeTerminate() {
    // if all jobs complete
    if(nrOfResults == nrOfWorks){
      //clear this round
      nrOfWorks = 0;
      nrOfResults = 0;
      finished  = true;
      return;
    }
  }

  private static ResultFromRR processRRTask(TaskForRR work) {
    final PointsToSetVariable user = work.getUser();
    final MutableIntSet targets = work.getTargets();
    final IPAPropagationSystem system = work.getPropagationSystem();
    ArrayList<PointsToSetVariable> next = new ArrayList<>();
    if(IPAAbstractFixedPointSolver.theRoot.contains(user)
        || system.getPropagationGraph().getNumberOfStatementsThatDef(user) == 0 //root
        || user.getValue() == null)
      return new ResultFromRR(user, next, (MutableSharedBitVectorIntSet) targets);
    //check
    final MutableSharedBitVectorIntSet remaining = new MutableSharedBitVectorIntSetFactory().makeCopy(targets);
    for (Iterator it = system.getPropagationGraph().getStatementsThatDef(user); it.hasNext();) {
      if(remaining.isEmpty())
        break;
      IPAUnaryStatement s = (IPAUnaryStatement) it.next();
      IVariable iv = s.getRightHandSide();
      if(iv instanceof PointsToSetVariable){
        PointsToSetVariable pv = (PointsToSetVariable)iv;
        if(pv.getValue() != null){
          IntSetAction action = new IntSetAction() {
            @Override
            public void act(int i) {
              if(remaining.isEmpty())
                return;
              if(targets.contains(i)){
                remaining.remove(i);
              }
            }
          };
          MutableIntSet set = pv.getValue();
          if(set != null){
            MutableIntSet set1;
            synchronized (pv) {
              set1 = IntSetUtil.makeMutableCopy(set);
            }
            set1.foreach(action);
          }else
            continue;
        }
      }
    }
    //check if changed
    if(!remaining.isEmpty()){
      MutableSharedBitVectorIntSet removed;
      synchronized (user) {
        removed = DeletionUtil.removeSome(user, remaining);//?sync
      }
      if(removed.size() > 0){
        IPAAbstractFixedPointSolver.addToChanges(user);
        //copy
        MutableIntSet copy;
        synchronized (user) {
          copy = IntSetUtil.makeMutableCopy(user.getValue());
        }
        //future
        for (Iterator it = system.getPropagationGraph().getStatementsThatUse(user); it.hasNext();) {
          IPAAbstractStatement s = (IPAAbstractStatement) it.next();
          IPAAbstractOperator op = s.getOperator();
          if(op instanceof IPAAssignOperator){
            PointsToSetVariable pv = (PointsToSetVariable) s.getLHS();
            if(pv.getValue() != null)
              next.add(pv);
          }else if(op instanceof IPAFilterOperator){
            IPAFilterOperator filter = (IPAFilterOperator) op;
            PointsToSetVariable pv = (PointsToSetVariable) s.getLHS();
            if(IPAAbstractFixedPointSolver.theRoot.contains(pv))
              continue;
            synchronized (pv) {
              byte mark = filter.evaluateDel(pv, (MutableSharedBitVectorIntSet)copy);
              if(mark == 1){
                IPAAbstractFixedPointSolver.addToChanges(pv);
                classifyPointsToConstraints(pv, copy, next, system);
              }
            }
          }else{
//            system.addToWorklistAkka(s);
            system.addToWorkListSync(s);
//            system.addToWorkList(s);
          }
        }
      }else{
        next = null;
      }
    }else{//all included, early return
      next = null;
    }

    return new ResultFromRR(user, next, remaining);
  }

  private static void classifyPointsToConstraints(PointsToSetVariable L, final MutableIntSet targets,
      ArrayList<PointsToSetVariable> next, IPAPropagationSystem system){
    for (Iterator it = system.getPropagationGraph().getStatementsThatUse(L); it.hasNext();) {
      IPAAbstractStatement s = (IPAAbstractStatement) it.next();
      IPAAbstractOperator op = s.getOperator();
      if(op instanceof IPAAssignOperator){
        PointsToSetVariable pv = (PointsToSetVariable) s.getLHS();
        if(pv.getValue() != null){
          next.add(pv);
        }
      }else if(op instanceof IPAFilterOperator){
        IPAFilterOperator filter = (IPAFilterOperator) op;
        PointsToSetVariable pv = (PointsToSetVariable) s.getLHS();
        if(IPAAbstractFixedPointSolver.theRoot.contains(pv))
          continue;
        byte mark = filter.evaluateDel(pv, (MutableSharedBitVectorIntSet)targets);
        if(mark == 1){
          IPAAbstractFixedPointSolver.addToChanges(pv);
          classifyPointsToConstraints(pv, targets, next, system);
        }
      }else{
//        system.addToWorklistAkka(s);
        system.addToWorkListSync(s);
//        system.addToWorkList(s);
      }
    }
  }

  private static ResultFromSpecial processSpecialWorkAddition(TaskForSpecial work) {
    final PointsToSetVariable user = work.getUser();
    final MutableIntSet targets = work.getTargets();
    final IPAPropagationSystem system = work.getPropagationSystem();
    ArrayList<PointsToSetVariable> next = new ArrayList<>();
    if(user.getValue() == null)
      return new ResultFromSpecial(user, next, (MutableSharedBitVectorIntSet) targets, work.getIsAdd());

    final MutableSharedBitVectorIntSet remaining = new MutableSharedBitVectorIntSetFactory().make();
    IntSetAction action = new IntSetAction() {
      @Override
      public void act(int i) {
        if(!user.contains(i)){
          remaining.add(i);
        }
      }
    };
    targets.foreach(action);

    if(!remaining.isEmpty()){
      synchronized (user) {
        user.addAll(remaining);
      }
      IPAAbstractFixedPointSolver.addToChanges(user);
//      further check
      for (Iterator it = system.getPropagationGraph().getStatementsThatUse(user); it.hasNext();) {
        IPAAbstractStatement s = (IPAAbstractStatement) it.next();
        IPAAbstractOperator op = s.getOperator();
        if(op instanceof IPAAssignOperator){
          PointsToSetVariable pv = (PointsToSetVariable) s.getLHS();
          if(pv.getValue() != null)
            next.add(pv);
        }else if(op instanceof IPAFilterOperator){
          IPAFilterOperator filter = (IPAFilterOperator) op;
          PointsToSetVariable pv = (PointsToSetVariable) s.getLHS();
          byte mark = filter.evaluate(pv, (PointsToSetVariable)((IPAUnaryStatement)s).getRightHandSide());
          if(mark == 1){
            IPAAbstractFixedPointSolver.addToChanges(pv);
            next.add(pv);
          }
        }else{
          system.addToWorkListSync(s);
        }
      }
    }else{
      next = null;
    }
    return new ResultFromSpecial(user, next, remaining, work.getIsAdd());
  }

  private static ResultFromSpecial processSpecialWorkDeletion(TaskForSpecial work) {
    final PointsToSetVariable user = work.getUser();
    final MutableIntSet targets = work.getTargets();
    final IPAPropagationSystem system = work.getPropagationSystem();
    ArrayList<PointsToSetVariable> next = new ArrayList<>();
    if(IPAAbstractFixedPointSolver.theRoot.contains(user)
        || system.getPropagationGraph().getNumberOfStatementsThatDef(user) == 0 //root
        || user.getValue() == null)
      return new ResultFromSpecial(user, next, (MutableSharedBitVectorIntSet) targets, work.getIsAdd());

    final MutableSharedBitVectorIntSet remaining = new MutableSharedBitVectorIntSetFactory().makeCopy(targets);
    for (Iterator it = system.getPropagationGraph().getStatementsThatDef(user); it.hasNext();) {
      if(remaining.isEmpty())
        break;
      IPAUnaryStatement s = (IPAUnaryStatement) it.next();
      IVariable iv = s.getRightHandSide();
      if(iv instanceof PointsToSetVariable){
        PointsToSetVariable pv = (PointsToSetVariable)iv;
        if(pv.getValue() != null){
          IntSetAction action = new IntSetAction() {
            @Override
            public void act(int i) {
              if(remaining.isEmpty())
                return;
              if(targets.contains(i)){
                remaining.remove(i);
              }
            }
          };
          MutableIntSet set = pv.getValue();
          if(set != null){
            MutableIntSet set1;
            synchronized (pv) {
              set1 = IntSetUtil.makeMutableCopy(set);
            }
            set1.foreach(action);
          }else
            continue;
        }
      }
    }

    if(!remaining.isEmpty()){
      MutableSharedBitVectorIntSet removed;
      synchronized (user) {
        removed = DeletionUtil.removeSome(user, remaining);//?sync
      }
      if(removed.size() > 0){
        IPAAbstractFixedPointSolver.addToChanges(user);
        //copy
        MutableIntSet copy;
        synchronized (user) {
          copy = IntSetUtil.makeMutableCopy(user.getValue());
        }
        //future
        for (Iterator it = system.getPropagationGraph().getStatementsThatUse(user); it.hasNext();) {
          IPAAbstractStatement s = (IPAAbstractStatement) it.next();
          IPAAbstractOperator op = s.getOperator();
          if(op instanceof IPAAssignOperator){
            PointsToSetVariable pv = (PointsToSetVariable) s.getLHS();
            if(pv.getValue() != null)
              next.add(pv);
          }else if(op instanceof IPAFilterOperator){
            IPAFilterOperator filter = (IPAFilterOperator) op;
            PointsToSetVariable pv = (PointsToSetVariable) s.getLHS();
            if(IPAAbstractFixedPointSolver.theRoot.contains(pv))
              continue;
            synchronized (pv) {
              byte mark = filter.evaluateDel(pv, (MutableSharedBitVectorIntSet)copy);
              if(mark == 1){
                IPAAbstractFixedPointSolver.addToChanges(pv);
                classifyPointsToConstraints(pv, copy, next, system);
              }
            }
          }else{
//            system.addToWorklistAkka(s);
            system.addToWorkListSync(s);
//            system.addToWorkList(s);
          }
        }
      }else{
        next = null;
      }
    }else{//all included, early return
      next = null;
    }
    return new ResultFromSpecial(user, next, remaining, work.getIsAdd());
  }



}
