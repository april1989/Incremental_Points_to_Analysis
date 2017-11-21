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


import com.ibm.wala.ipa.callgraph.propagation.PointsToSetVariable;
import com.ibm.wala.util.intset.MutableIntSet;

import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationSystem;

public class TaskForSpecial {

  private PointsToSetVariable first;
  private MutableIntSet targets;
  private boolean isAddition ;
  private IPAPropagationSystem system;

  public TaskForSpecial(PointsToSetVariable first, MutableIntSet targets, boolean isAddition,
      IPAPropagationSystem system) {
    this.first = first;
    this.targets = targets;
    this.isAddition = isAddition;
    this.system = system;
  }


  public IPAPropagationSystem getPropagationSystem(){
    return system;
  }

  public MutableIntSet getTargets(){
    return targets;
  }

  public PointsToSetVariable getUser(){
    return first;
  }

  public boolean getIsAdd(){
    return isAddition;
  }

}
