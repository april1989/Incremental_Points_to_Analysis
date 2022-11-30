package edu.tamu.wala.increpta.instancekey;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.propagation.AllocationSiteInNode;

public class EventNormalAllocationInNode  extends AllocationSiteInNode {

	public EventNormalAllocationInNode(CGNode node, NewSiteReference allocation, IClass type) {
		super(node, allocation, type);
	}

	@Override
	public boolean equals(Object obj) {
		if (obj instanceof EventNormalAllocationInNode) {
			EventNormalAllocationInNode other = (EventNormalAllocationInNode) obj;
			return getNode().equals(other.getNode()) && getSite().equals(other.getSite());
		}
		return false;
	}

	@Override
	public int hashCode() {
		return getNode().hashCode() * 5647 + getSite().hashCode() + 131;
	}
	
}
