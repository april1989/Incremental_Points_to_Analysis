package edu.tamu.wala.newkobj;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.ContextItem;
import com.ibm.wala.ipa.callgraph.ContextKey;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;


/**
 * 
 * @author Bozhen
 *
 */
public class ReceiverStringContext implements Context {
	private final ReceiverString receiverString;

	public ReceiverStringContext(ReceiverString receiverString) {
		if (receiverString == null) {
			throw new IllegalArgumentException("null receiverString");
		}
		this.receiverString = receiverString;
	}


	@Override
	public boolean equals(Object o) {
		return (o instanceof ReceiverStringContext) && ((ReceiverStringContext) o).receiverString.equals(receiverString);
	}

	@Override
	public int hashCode() {
		return receiverString.hashCode();
	}

	@Override
	public String toString() {
		return "ReceiverStringContext: " + receiverString.toString();
	}

	public ContextItem get(ContextKey name) {
		if (KObjectSensitiveContextSelector.RECEIVER_STRING.equals(name)) {
			return receiverString;
		} else {
			return null;
		}
	}
}