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
///*******************************************************************************
// * Copyright (c) 2007 IBM Corporation.
// * All rights reserved. This program and the accompanying materials
// * are made available under the terms of the Eclipse Public License v1.0
// * which accompanies this distribution, and is available at
// * http://www.eclipse.org/legal/epl-v10.html
// *
// * Contributors:
// *     IBM Corporation - initial API and implementation
// *******************************************************************************/
//package com.ibm.wala.incre.parallel;
//
//
//import java.util.ArrayList;
//import java.util.Iterator;
//
//import com.ibm.wala.incre.ipa.callgraph.propagation.IPAPropagationSystem;
//import com.ibm.wala.ipa.callgraph.propagation.PointsToSetVariable;
//import com.ibm.wala.util.intset.MutableIntSet;
//
//import akka.actor.ActorRef;
//import akka.actor.Props;
//import akka.actor.UntypedActor;
//import akka.routing.BalancingPool;

//created for akka, same functionality with ThreadHub extends UntypedActor
//
//public class AkkaHub extends UntypedActor{
//
//  private final int nrOfWorkers;
//  private MutableIntSet targets;
////  private ArrayList<Result> results;
//  private int nrOfResults;
//  private int nrOfWorks;
//  private final ActorRef workerRouter;
//  private static boolean finished = false;
//
//  private IPAPropagationSystem system;
//
//  /**
//   * the hub (scheduler) for received tasks of add/del points-to constraints
//   * @param nrOfWorkers
//   */
//  public AkkaHub(final int nrOfWorkers) {
//    this.nrOfWorkers = nrOfWorkers;
//    Props props = Props.create(Worker.class).withRouter(new BalancingPool(nrOfWorkers));
//    workerRouter = this.getContext().actorOf(props, "workerRouter");
//  }
//
//  @Override
//  public void onReceive(Object message) throws Throwable {
//    if(message instanceof SchedulerForRRTasks){
//      SchedulerForRRTasks work = (SchedulerForRRTasks) message;
//      processRRTasks(work);
//    }else if(message instanceof SchedulerForSpecialTasks){
//      SchedulerForSpecialTasks work = (SchedulerForSpecialTasks) message;
//      processSpecial(work);
//    }else if(message instanceof ResultFromRR){
//      ResultFromRR result = (ResultFromRR) message;
//      analyzeResultFromRR(result);
//    }else if(message instanceof ResultFromSpecial){
//      ResultFromSpecial result = (ResultFromSpecial) message;
//      analyzeResultFromSpecial(result);
//    }else{
//      unhandled(message);
//    }
//  }
//
//  private void processSpecial(SchedulerForSpecialTasks work) {
//    // initial job distribution
//    MutableIntSet targets = work.getTargets();
//    system = work.getPropagationSystem();
//    Iterator<PointsToSetVariable> lhss = work.getLhss().iterator();
//    boolean op = work.getIsAddition();
//    while(lhss.hasNext()){
//      PointsToSetVariable lhs = lhss.next();
//      if(lhs.getPointerKey().toString().contains("[<Primordial,[Ljava/util/regex/UnicodeProp>][]"))
//        System.out.println();
//      TaskForSpecial job = new TaskForSpecial(lhs, targets, op, system);
//      nrOfWorks++;
//      workerRouter.tell(job, getSelf());
//    }
//  }
//
//  private void processRRTasks(SchedulerForRRTasks work) {
//    // initial job distribution
//    MutableIntSet targets = work.getTargets();
//    system = work.getPropagationSystem();
//    Iterator<PointsToSetVariable> users = work.getFirstUsers().iterator();
//    while(users.hasNext()){
//      PointsToSetVariable user = users.next();
//      if(user.getPointerKey().toString().contains("[<Primordial,[Ljava/util/regex/UnicodeProp>][]"))
//        System.out.println();
//      TaskForRR job = new TaskForRR(user, targets, system);
//      nrOfWorks++;
//      workerRouter.tell(job, getSelf());
//    }
//  }
//
//
//  private void analyzeResultFromRR(ResultFromRR result) {
//    nrOfResults++;
//    MutableIntSet newtarget = result.getNewTargets();
//    ArrayList<PointsToSetVariable> nexts = result.getCheckNext();
//    if(nexts != null){
//      if(!nexts.isEmpty() && newtarget.size() > 0){
//        Iterator<PointsToSetVariable> iterator = nexts.iterator();
//        while(iterator.hasNext()){
//          PointsToSetVariable next = iterator.next();
//          if(next.getPointerKey().toString().contains("[<Primordial,[Ljava/util/regex/UnicodeProp>][]"))
//            System.out.println();
//          if(next.getValue() != null){
//            TaskForRR job = new TaskForRR(next, newtarget, system);
//            nrOfWorks++;
//            workerRouter.tell(job, getSelf());
//          }
//        }
//      }
//    }
//    doWeTerminate();
//  }
//
//  private void doWeTerminate() {
//    // if all jobs complete
//    if(nrOfResults == nrOfWorks){
//      //clear this round
//      nrOfWorks = 0;
//      nrOfResults = 0;
//      finished  = true;
//      system = null;
//    }
//  }
//
//  private void analyzeResultFromSpecial(ResultFromSpecial result) {
//    nrOfResults++;
//    MutableIntSet newtarget = result.getNewTargets();
//    ArrayList<PointsToSetVariable> nexts = result.getCheckNext();
//    boolean isAdd = result.getIsAdd();
//    if(nexts != null){
//      if(!nexts.isEmpty() && newtarget.size() > 0){
//        Iterator<PointsToSetVariable> iterator = nexts.iterator();
//        while(iterator.hasNext()){
//          PointsToSetVariable next = iterator.next();
//          if(next.getPointerKey().toString().contains("[<Primordial,[Ljava/util/regex/UnicodeProp>][]"))
//            System.out.println();
//          if(next.getValue() != null){
//            TaskForSpecial job = new TaskForSpecial(next, newtarget, isAdd, system);
//            nrOfWorks++;
//            workerRouter.tell(job, getSelf());
//          }
//        }
//      }
//    }
//    doWeTerminate();
//  }
//
//  public static boolean askstatus(){
//    if(finished){
//      finished = false;
//      return false;
//    }else{
//      return true;
//    }
//  }
//
//}
