package edu.tamu.wala.increpta.callgraph.impl;

import java.util.HashSet;

import com.ibm.wala.ssa.SSAConditionalBranchInstruction;

/**
 * bz: a class to remember the def-use for both branches for a condition.
 */
public class DUForPath {
	  /**
	   * this DUForPath is for this instruction
	   */
	  private SSAConditionalBranchInstruction instruction;
	  HashSet<Integer> remove_ids = new HashSet<>();
	  HashSet<Integer> keep_ids = new HashSet<>();

	  /**
	   * bz: a class to remember the def-use for both branches for a condition.
	   */
	  public DUForPath(SSAConditionalBranchInstruction cond) {
		  this.instruction = cond;
	  }

	  public HashSet<Integer> getRemoveIDs() {
		  return remove_ids;
	  }

	  public HashSet<Integer> getKeepIDs() {
		  return keep_ids;
	  }

	  public void addRemove(int def) {
		  remove_ids.add(def);
	  }

	  public void addKeep(int def) {
		  keep_ids.add(def);
	  }
}
	