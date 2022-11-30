package edu.tamu.wala.increpta.instancekey;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.classLoader.ProgramCounter;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.propagation.AllocationSiteInNodeFactory;
import com.ibm.wala.ipa.callgraph.propagation.ConstantKey;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKeyFactory;
import com.ibm.wala.ipa.callgraph.propagation.NullConstantKey;
import com.ibm.wala.ipa.callgraph.propagation.SmushedAllocationSiteInstanceKeys;
import com.ibm.wala.ipa.callgraph.propagation.rta.RTAContextInterpreter;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.collections.Iterator2Iterable;


public class IPAZeroXInstanceKeys extends AbstractIPAZeroXInstanceKeys{
	

	/**
	 * A delegate object to create class-based abstract instances
	 */
	private final IPAClassBasedInstanceKeys classBased;

	/**
	 * A delegate object to create allocation site-based abstract instances
	 */
	private final AllocationSiteInNodeFactory siteBased;

	/**
	 * A delegate object to create "abstract allocation site" - based abstract instances
	 */
	private final SmushedAllocationSiteInstanceKeys smushed;


	/**
	 * Diff: handle class-based instance with origin/thread
	 * @param options
	 * @param cha
	 * @param contextInterpreter
	 * @param policy
	 */
	public IPAZeroXInstanceKeys(AnalysisOptions options, IClassHierarchy cha, RTAContextInterpreter contextInterpreter, int policy) {
		super(options, cha, contextInterpreter, policy);
		classBased = new IPAClassBasedInstanceKeys(options, cha);//class-base + origin
		siteBased = new AllocationSiteInNodeFactory(options, cha);
		smushed = new SmushedAllocationSiteInstanceKeys(options, cha);
	}


	@Override
	public InstanceKey getInstanceKeyForAllocation(CGNode node, NewSiteReference allocation) {
		if (allocation == null) {
			throw new IllegalArgumentException("allocation is null");
		}
		TypeReference t = allocation.getDeclaredType();
		IClass C = getClassHierarchy().lookupClass(t);

		if (C != null && isInteresting(C)) {
			if (smushMany()) {
				if (exceedsSmushLimit(C, node)) {
					return smushed.getInstanceKeyForAllocation(node, allocation);
				} else {
					return siteBased.getInstanceKeyForAllocation(node, allocation);
				}
			} else {
				return siteBased.getInstanceKeyForAllocation(node, allocation);
			}
		} else {
			return classBased.getInstanceKeyForAllocation(node, allocation);
		}
	}


	@Override
	public InstanceKey getInstanceKeyForMultiNewArray(CGNode node, NewSiteReference allocation, int dim) {
		if (allocationPolicy()) {
			return siteBased.getInstanceKeyForMultiNewArray(node, allocation, dim);
		} else {
			return classBased.getInstanceKeyForMultiNewArray(node, allocation, dim);
		}
	}

	@Override
	public <T> InstanceKey getInstanceKeyForConstant(TypeReference type, T S) {
		if (type == null) {
			throw new IllegalArgumentException("null type");
		}
		if (disambiguateConstants() || isReflectiveType(type)) {
			if(TypeReference.isNullType(type))
				return new NullConstantKey<>(S, getClassHierarchy().lookupClass(type));
			else
				return new ConstantKey<>(S, getClassHierarchy().lookupClass(type));
		} else {
			return classBased.getInstanceKeyForConstant(type, S);
		}
	}


	/*
	 * @see com.ibm.wala.ipa.callgraph.propagation.InstanceKeyFactory#getInstanceKeyForPEI(com.ibm.wala.ipa.callgraph.CGNode,
	 * com.ibm.wala.classLoader.ProgramCounter, com.ibm.wala.types.TypeReference)
	 */
	@Override
	public InstanceKey getInstanceKeyForPEI(CGNode node, ProgramCounter pei, TypeReference type) {
		return classBased.getInstanceKeyForPEI(node, pei, type);
	}

	@Override
	public InstanceKey getInstanceKeyForMetadataObject(Object obj, TypeReference objType) {
		return classBased.getInstanceKeyForMetadataObject(obj, objType);
	}

}
