package edu.tamu.wala.increpta.ipa.callgraph.propagation;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.propagation.LocalPointerKey;

public class IPALocalPointerKeyWithFilter extends LocalPointerKey implements IPAFilteredPointerKey {

	private final IPATypeFilter typeFilter;

	public IPALocalPointerKeyWithFilter(CGNode node, int valueNumber, IPATypeFilter typeFilter) {
		super(node,valueNumber);
		assert typeFilter != null;
		this.typeFilter = typeFilter;
	}


	@Override
	public IPATypeFilter getTypeFilter() {
		return typeFilter;
	}

}