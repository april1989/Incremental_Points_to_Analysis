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

import com.ibm.wala.ipa.callgraph.propagation.PointsToSetVariable;
import com.ibm.wala.util.intset.MutableIntSet;

import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationSystem;


public class SchedulerForRRTasks {
  private final MutableIntSet targets;
  private ArrayList<PointsToSetVariable> firstUsers;
  private final IPAPropagationSystem system;

  public SchedulerForRRTasks(MutableIntSet targets, ArrayList<PointsToSetVariable> firstUsers,
		  IPAPropagationSystem system){
    this.targets = targets;
    this.firstUsers = firstUsers;
    this.system = system;
  }

  public IPAPropagationSystem getPropagationSystem(){
    return system;
  }

  public ArrayList<PointsToSetVariable> getFirstUsers(){
    return  firstUsers;
  }

  public MutableIntSet getTargets(){
    return targets;
  }

}
