package edu.tamu.wala.increpta.scc;

import com.ibm.wala.ipa.callgraph.propagation.AbstractPointerKey;

public class SSCPointerKey extends AbstractPointerKey{

	private String name;

	/**
	 * this is a fake pointerkey for scc
	 * @param name
	 */
	public SSCPointerKey(String name) {
		this.name = name;//scc
	}

	public String getName() {
		return name;
	}

	@Override
	public boolean equals(Object that) {
		if(that instanceof SSCPointerKey){
			if(this.name.equals(((SSCPointerKey) that).getName())){
				return true;
			}
		}
		return false;
	}

	@Override
	public int hashCode() {
		return name.hashCode();
	}



}
