package edu.tamu.wala.increpta.ipa.callgraph.propagation;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.propagation.ReturnValueKey;

public class IPAReturnValueKeyWithFilter extends ReturnValueKey implements IPAFilteredPointerKey {

	  private final IPATypeFilter typeFilter;

	  public IPAReturnValueKeyWithFilter(CGNode node, IPATypeFilter typeFilter) {
	    super(node);
	    if (typeFilter == null) {
	      throw new IllegalArgumentException("null typeFilter");
	    }
	    this.typeFilter = typeFilter;
	  }

	  @Override
	  public IPATypeFilter getTypeFilter() {
	    return typeFilter;
	  }

	}
