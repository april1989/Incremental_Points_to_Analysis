package edu.tamu.wala.increpta.instancekey;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.propagation.AllocationSiteInNode;

public final class ThreadNormalAllocationInNode extends AllocationSiteInNode{

	private CGNode run_target;
	private CGNode join_target;

	public ThreadNormalAllocationInNode(CGNode node, NewSiteReference allocation, IClass type) {
		super(node, allocation, type);
	}
	
	public void setRunTarget(CGNode target) {
		if(this.run_target == null)
			this.run_target = target;
	}
	
	public void removeRunTarget() {
		this.run_target = null;
	}
	
	public CGNode getRunTarget() {
		return run_target;
	}
	
	public void setJoinTarget(CGNode target) {
		if(this.join_target == null)
			this.join_target = target;
	}
	
	public void removeJoinTarget() {
		this.join_target = null;
	}
	
	public CGNode getJoinTarget() {
		return join_target;
	}
	
	@Override
	public boolean equals(Object obj) {
		if (obj instanceof ThreadNormalAllocationInNode) {
			ThreadNormalAllocationInNode other = (ThreadNormalAllocationInNode) obj;
			return getNode().equals(other.getNode()) && getSite().equals(other.getSite());
		} else {
			return false;
		}
	}

	@Override
	public int hashCode() {
		return getNode().hashCode() * 8647 + getSite().hashCode() + 131;
	}

}
