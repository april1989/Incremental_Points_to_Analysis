package edu.tamu.wala.increpta.ipa.callgraph.propagation;

import java.util.Iterator;

import com.ibm.wala.ipa.callgraph.propagation.InstanceKeyFactory;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.cha.IClassHierarchy;

public interface IPAHeapModel extends InstanceKeyFactory, IPAPointerKeyFactory {

	/**
	 * @return an Iterator of all PointerKeys that are modeled.
	 */
	Iterator<PointerKey> iteratePointerKeys();

	/**
	 * @return the governing class hierarchy for this heap model
	 */
	IClassHierarchy getClassHierarchy();

}