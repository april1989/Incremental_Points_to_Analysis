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

public class SchedulerForSpecialTasks {
  private  ArrayList<PointsToSetVariable> lhss;
  private  boolean isAddition;
  private  MutableIntSet targets;
  private  IPAPropagationSystem system;

  public SchedulerForSpecialTasks(ArrayList<PointsToSetVariable> lhss,
       MutableIntSet targets,  boolean isAddition,  IPAPropagationSystem system){
    this.lhss = lhss;
    this.targets = targets;
    this.isAddition = isAddition;
    this.system = system;
  }

  public IPAPropagationSystem getPropagationSystem(){
    return system;
  }

  public ArrayList<PointsToSetVariable> getLhss(){
    return lhss;
  }

  public MutableIntSet getTargets(){
    return targets;
  }

  public boolean getIsAddition(){
    return isAddition;
  }


}
