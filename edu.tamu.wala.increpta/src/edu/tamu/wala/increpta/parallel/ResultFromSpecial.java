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

import com.ibm.wala.ipa.callgraph.propagation.PointsToSetVariable;
import com.ibm.wala.util.intset.MutableSharedBitVectorIntSet;


public class ResultFromSpecial {

  private PointsToSetVariable user;
  private ArrayList<PointsToSetVariable> next;
  private MutableSharedBitVectorIntSet newtargets;
  private boolean isAdd;

  public ResultFromSpecial(PointsToSetVariable user, ArrayList<PointsToSetVariable> next, MutableSharedBitVectorIntSet remaining, boolean isAdd) {
    this.user = user;
    this.newtargets = remaining;
    this.next = next;
    this.isAdd = isAdd;
  }

  public PointsToSetVariable getUser(){
    return user;
  }

  public ArrayList<PointsToSetVariable> getCheckNext(){
    return next;
  }

  public MutableSharedBitVectorIntSet getNewTargets(){
    return newtargets;
  }

  public boolean getIsAdd(){
    return isAdd;
  }


}
