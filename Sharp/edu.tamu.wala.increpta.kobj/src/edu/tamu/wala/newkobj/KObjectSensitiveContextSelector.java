package edu.tamu.wala.newkobj;

import com.ibm.wala.analysis.reflection.JavaTypeContext;
import com.ibm.wala.analysis.typeInference.PointType;
import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.ContextKey;
import com.ibm.wala.ipa.callgraph.ContextSelector;
import com.ibm.wala.ipa.callgraph.DelegatingContext;
import com.ibm.wala.ipa.callgraph.propagation.AllocationSiteInNode;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.util.intset.EmptyIntSet;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.IntSetUtil;

import edu.tamu.wala.increpta.util.JavaUtil;


/**
 * Context is similar to com.ibm.wala.ipa.callgraph.propagation.cfa.CallString, 
 * use NewSiteReference[] and IMethod[] to replace InstanceKey[] (-> this is too strict which leads to non-stop running pta)
 * 
 * @author Bozhen
 */
public class KObjectSensitiveContextSelector implements ContextSelector {

	private final int objectSensitivityLevel;
	private ContextSelector base; //reflection lol -> not considered now

	public KObjectSensitiveContextSelector(int objectSensitivityLevel, ContextSelector base) {
		this.objectSensitivityLevel = objectSensitivityLevel;
		this.base = base;
	}

	public static final ContextKey RECEIVER_STRING = new ContextKey() {
		@Override
		public String toString() {
			return "RECEIVER_STRING_KEY";
		}
	};
	
	public int getN() {
		return objectSensitivityLevel;
	}

	@Override
	public Context getCalleeTarget(CGNode caller, CallSiteReference site, IMethod callee, InstanceKey[] actualParameters) {
		//--> !!!! bz: use DelegatingContext, otherwise reflection context selectors have no idea what should be created, since they do not understand them with a receiverstring wrapper  
	    Context baseContext = base.getCalleeTarget(caller, site, callee, actualParameters);
		if(baseContext instanceof DelegatingContext)
	    	return baseContext;
		
	    // kobj algo 
		if (actualParameters == null || actualParameters.length == 0 || actualParameters[0] == null) {
			// Provide a distinguishing context even when the receiver is null (e.g. in case of an invocation of a static method)
		    return caller.getContext();
		}
		
		InstanceKey receiver = actualParameters[0];
		if (JavaUtil.isObjectGetClass(callee)) {
			return classifyReceiverContext(receiver, caller.getContext());
		}
		else if (JavaUtil.isJDKClass(callee.getDeclaringClass())) { //JavaUtil.isLibraryClass(callee.getDeclaringClass()) || 
			return createTypeContext(receiver);
		} 
		else if (objectSensitivityLevel == 0) {
			return createTypeContext(receiver);
		} else {
			return classifyReceiverContext(receiver, caller.getContext());
		}
	}

	private Context classifyReceiverContext(InstanceKey receiver, Context callerContext) {
		if(receiver instanceof AllocationSiteInNode) {
			AllocationSiteInNode alloc_receiver = (AllocationSiteInNode) receiver;
			return createReceiverContext(alloc_receiver.getSite(), alloc_receiver.getNode().getMethod(), callerContext);
		}else {
			return createTypeContext(receiver);
		}
	}
	
	private Context createReceiverContext(NewSiteReference site, IMethod method, Context callerContext) {
		ReceiverString receiverString;
		if (!(callerContext instanceof ReceiverStringContext)) {
			receiverString = new ReceiverString(site, method);
		} else {
			ReceiverString callerReceiverString = (ReceiverString) ((ReceiverStringContext) callerContext).get(RECEIVER_STRING);
			receiverString = new ReceiverString(site, method, objectSensitivityLevel, callerReceiverString);
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
//			return EmptyIntSet.instance;  
			return base.getRelevantParameters(caller, site); 
		}
	}

}
