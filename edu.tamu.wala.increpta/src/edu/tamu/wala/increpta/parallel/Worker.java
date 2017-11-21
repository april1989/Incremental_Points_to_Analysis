/*******************************************************************************
 * Copyright (c) 2007 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package edu.tamu.wala.increpta.parallel;

import java.util.ArrayList;
import java.util.Iterator;

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

//import akka.actor.UntypedActor;

public class Worker {

	//created for akka, extends UntypedActor
//  @Override
//  public void onReceive(Object message) throws Exception {
//    if(message instanceof TaskForRR){
//      TaskForRR work = (TaskForRR) message;
//      ResultFromRR next = processRRTask(work);
//      getSender().tell(next, getSelf());
//    }else if(message instanceof TaskForSpecial){
//      TaskForSpecial work = (TaskForSpecial) message;
//      final boolean isAddition = work.getIsAdd();
//      ResultFromSpecial result;
//      if(isAddition){
//        result = processSpecialWorkAddition(work);
//      }else{
//        result = processSpecialWorkDeletion(work);
//      }
//      getSender().tell(result, getSelf());
//    }else{
//      unhandled(message);
//    }
//  }

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
//          system.addToWorklistAkka(s);
          system.addToWorkListSync(s);
//          system.addToWorkList(s);
        }
      }
    }else{
      next = null;
    }
    return new ResultFromSpecial(user, next, remaining, work.getIsAdd());
  }

  private ResultFromSpecial processSpecialWorkDeletion(TaskForSpecial work) {
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


  private ResultFromRR processRRTask(TaskForRR work) {
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

  private void classifyPointsToConstraints(PointsToSetVariable L, final MutableIntSet targets,
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
        synchronized (pv) {
          byte mark = filter.evaluateDel(pv, (MutableSharedBitVectorIntSet)targets);
          if(mark == 1){
            IPAAbstractFixedPointSolver.addToChanges(pv);
            classifyPointsToConstraints(pv, targets, next, system);
          }
        }
      }else{
//        system.addToWorklistAkka(s);
        system.addToWorkListSync(s);
//        system.addToWorkList(s);
      }
    }
  }


}
