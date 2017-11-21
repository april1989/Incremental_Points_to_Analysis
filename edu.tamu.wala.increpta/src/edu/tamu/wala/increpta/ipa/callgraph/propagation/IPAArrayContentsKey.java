package edu.tamu.wala.increpta.ipa.callgraph.propagation;

import com.ibm.wala.ipa.callgraph.propagation.AbstractFieldPointerKey;
import com.ibm.wala.ipa.callgraph.propagation.ArrayContentsKey;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.classLoader.ArrayClass;

public final class IPAArrayContentsKey extends AbstractFieldPointerKey implements IPAFilteredPointerKey {
	public IPAArrayContentsKey(InstanceKey instance) {
		super(instance);
	}

	@Override
	public boolean equals(Object obj) {
		if (obj instanceof ArrayContentsKey) {
			ArrayContentsKey other = (ArrayContentsKey) obj;
			return instance.equals(other.getInstanceKey());
		} else {
			return false;
		}
	}

	@Override
	public int hashCode() {
		return 1061 * instance.hashCode();
	}

	@Override
	public String toString() {
		return "[" + instance + "[]]";
	}

	/*
	 * @see com.ibm.wala.ipa.callgraph.propagation.PointerKey#getTypeFilter()
	 */
	@Override
	public IPATypeFilter getTypeFilter() {
		return new SingleClassFilter(((ArrayClass) instance.getConcreteType()).getElementClass());
	}
}
