package edu.tamu.wala.increpta.instancekey;

import java.util.Collection;
import java.util.Iterator;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.ConcreteTypeKey;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.collections.ComposedIterator;
import com.ibm.wala.util.collections.FilterIterator;
import com.ibm.wala.util.collections.MapIterator;
import com.ibm.wala.util.collections.Pair;
import com.ibm.wala.util.debug.Assertions;



/*
 * An instance key which represents a unique set for each concrete type
 * with its origin
 */
public class IPAConcreteTypeKey implements InstanceKey{
	
	private final IClass type;

	private final Context origin;

	public IPAConcreteTypeKey(IClass type, Context origin) {
		if (type == null) {
			throw new IllegalArgumentException("type is null");
		}
		if (origin == null) {
			throw new IllegalArgumentException("origin is null");
		}
		if (type.isInterface()) {
			Assertions.UNREACHABLE("unexpected interface: " + type);
		}
		this.type = type;
		this.origin = origin;
	}

	@Override
	public boolean equals(Object obj) {
		if (obj instanceof IPAConcreteTypeKey) {
			IPAConcreteTypeKey other = (IPAConcreteTypeKey) obj;
			return type.equals(other.type) && origin.equals(other.origin);
		}
		return false;
	}

	@Override
	public int hashCode() {
		return 461 * type.hashCode() + 11 * origin.hashCode();
	}

	@Override
	public String toString() {
		return "[" + type + "@" + origin + ']';
	}

	public IClass getType() {
		return type;
	}
	
	public Context getContext() {
		return origin;
	}

	/*
	 * @see com.ibm.wala.ipa.callgraph.propagation.InstanceKey#getConcreteType()
	 */
	@Override
	public IClass getConcreteType() {
		return type;
	}

	/**
	 * bz: actually never been used...
	 */
	public static InstanceKey[] getInstanceKeysForPEI(SSAInstruction pei, IClassHierarchy cha) {
		if (pei == null) {
			throw new IllegalArgumentException("pei is null");
		}
		Collection<TypeReference> types = pei.getExceptionTypes();
		// TODO: institute a cache?
		if (types == null) {
			return null;
		}
		InstanceKey[] result = new InstanceKey[types.size()];
		int i = 0;
		for (TypeReference type : types) {
			assert type != null;
			IClass klass = cha.lookupClass(type);
			result[i++] = new ConcreteTypeKey(klass);//?
		}
		return result;
	}

	/**
	 * bz: actually should not used...
	 */
	@Override
	public Iterator<Pair<CGNode, NewSiteReference>> getCreationSites(CallGraph CG) {
		return new ComposedIterator<CGNode, Pair<CGNode, NewSiteReference>>(CG.iterator()) {
			@Override
			public Iterator<? extends Pair<CGNode, NewSiteReference>> makeInner(final CGNode outer) {
				return new MapIterator<>(
						new FilterIterator<>(
								outer.iterateNewSites(),
								o -> o.getDeclaredType().equals(type.getReference())
								),
						object -> Pair.make(outer, object));
			}
		};
	}

}
