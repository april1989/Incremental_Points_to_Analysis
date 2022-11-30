package edu.tamu.wala.increpta.ipa.callgraph.propagation.typesens;

import com.ibm.wala.analysis.reflection.JavaTypeContext;
import com.ibm.wala.analysis.typeInference.PointType;
import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.ContextKey;
import com.ibm.wala.ipa.callgraph.ContextSelector;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.util.intset.EmptyIntSet;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.IntSetUtil;

import edu.tamu.wala.increpta.util.JavaUtil;
import edu.tamu.wala.newkobj.ReceiverString;
import edu.tamu.wala.newkobj.ReceiverStringContext;

/**
 * not done
 *
 */
public class TypeContextSelector implements ContextSelector{

	private final int objectSensitivityLevel;

	/**
	 * Instead of using allocation sites, it uses the name of the class containing the allocation site
	 * to represent contexts. For example, an object node ⟨o, [t1, t2, ...tk ]⟩ represents
	 * the receiver object o1 of o is created in class t1, which has a receiver object o2 created in class t2.
	 * This continues until it reaches the k receiver object ok created in class tk .
	 *
	 * Yannis Smaragdakis, Martin Bravenboer, and Ondrej Lhoták. 2011.
	 * Pick Your Contexts Well: Understanding Object-sensitivity
	 * (POPL ’11).
	 */
	public TypeContextSelector(int n) {
		this.objectSensitivityLevel = n;
	}

	public static final ContextKey RECEIVER_STRING = new ContextKey() {
		@Override
		public String toString() {
			return "TYPE_STRING_KEY";
		}
	};

	@Override
	public Context getCalleeTarget(CGNode caller, CallSiteReference site, IMethod callee, InstanceKey[] actualParameters) {
		if (actualParameters == null || actualParameters.length == 0 || actualParameters[0] == null) {
			// Provide a distinguishing context even when the receiver is null (e.g. in case of an invocation of a static method)
			return caller.getContext();
		}
		InstanceKey receiver = actualParameters[0];
		if (JavaUtil.isObjectGetClass(callee)) {
			return createReceiverContext(receiver, caller.getContext());
		} else if (JavaUtil.isLibraryClass(callee.getDeclaringClass()) || JavaUtil.isJDKClass(callee.getDeclaringClass())) {
			return createTypeContext(receiver);
		} else if (objectSensitivityLevel == 0) {
			return createTypeContext(receiver);
		} else {
			return createReceiverContext(receiver, caller.getContext());
		}
	}

	private Context createReceiverContext(InstanceKey receiver, Context callerContext) {
		ReceiverString receiverString;
		if (!(callerContext instanceof ReceiverStringContext)) {
			receiverString = new ReceiverString(receiver);
		} else {
			ReceiverString callerReceiverString = (ReceiverString) ((ReceiverStringContext) callerContext).get(RECEIVER_STRING);
			receiverString = new ReceiverString(receiver, objectSensitivityLevel, callerReceiverString);
		}
		return new ReceiverStringContext(receiverString);
	}

	private Context createTypeContext(InstanceKey receiver) {
		return new JavaTypeContext(new PointType(receiver.getConcreteType()));
	}

	private static final IntSet receiver = IntSetUtil.make(new int[] { 0 });

	@Override
	public IntSet getRelevantParameters(CGNode caller, CallSiteReference site) {
		if (site.isDispatch() || site.getDeclaredTarget().getNumberOfParameters() > 0) {
			return receiver;
		} else {
			return EmptyIntSet.instance;
		}
	}

}
