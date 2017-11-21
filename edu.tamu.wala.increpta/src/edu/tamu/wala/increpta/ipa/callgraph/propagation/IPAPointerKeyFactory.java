package edu.tamu.wala.increpta.ipa.callgraph.propagation;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.propagation.PointerKeyFactory;

public interface IPAPointerKeyFactory extends PointerKeyFactory{

	  /**
	   * @return the PointerKey that acts as a representative for the class of pointers that includes the local variable identified by
	   *         the value number parameter.
	   * for the new IPAFilteredPointerKey
	   */
	  IPAFilteredPointerKey getFilteredPointerKeyForLocal(CGNode node, int valueNumber, IPAFilteredPointerKey.IPATypeFilter filter);

}
