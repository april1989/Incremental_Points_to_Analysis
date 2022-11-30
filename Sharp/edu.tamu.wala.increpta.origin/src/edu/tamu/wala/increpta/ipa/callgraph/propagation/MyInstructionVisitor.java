package edu.tamu.wala.increpta.ipa.callgraph.propagation;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IRView;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSANewInstruction;


/**
 * to conviniently borrow the methods in IPASSAPropagationCallGraphBuilder
 * @author bozhen
 *
 */
public abstract class MyInstructionVisitor extends SSAInstruction.Visitor {
		
	public abstract void updateOriginSensitiveObjectForNew(int type, CGNode ciCGNode, CGNode originCGNode, SSANewInstruction instruction);

	public abstract void setBasicBlock(ISSABasicBlock block);
	public abstract IRView getIR();
}
