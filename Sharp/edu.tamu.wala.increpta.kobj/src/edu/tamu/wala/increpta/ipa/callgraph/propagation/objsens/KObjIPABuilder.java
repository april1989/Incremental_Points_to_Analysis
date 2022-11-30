package edu.tamu.wala.increpta.ipa.callgraph.propagation.objsens;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import com.ibm.wala.analysis.reflection.JavaTypeContext;
import com.ibm.wala.analysis.reflection.ReflectionContextInterpreter;
import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.ContextItem;
import com.ibm.wala.ipa.callgraph.ContextSelector;
import com.ibm.wala.ipa.callgraph.DelegatingContext;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.callgraph.impl.AbstractRootMethod;
import com.ibm.wala.ipa.callgraph.impl.DefaultContextSelector;
import com.ibm.wala.ipa.callgraph.impl.DelegatingContextSelector;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.SSAContextInterpreter;
import com.ibm.wala.ipa.callgraph.propagation.cfa.CallString;
import com.ibm.wala.ipa.callgraph.propagation.cfa.CallStringContextSelector;
import com.ibm.wala.ipa.callgraph.propagation.cfa.CallerSiteContext;
import com.ibm.wala.ipa.callgraph.propagation.cfa.DefaultSSAInterpreter;
import com.ibm.wala.ipa.callgraph.propagation.cfa.DelegatingSSAContextInterpreter;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.util.CancelException;

import edu.tamu.wala.increpta.callgraph.impl.IPAExplicitCallGraph.IPAExplicitNode;
import edu.tamu.wala.increpta.change.IRChangedSummary;
import edu.tamu.wala.increpta.change.IRDiff;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.bridge.AbstractKIPASSAPropagationCallGraphBuilder;
import edu.tamu.wala.increpta.pointerkey.IPADefaultPointerKeyFactory;
import edu.tamu.wala.increpta.pointerkey.IPAPointerKeyFactory;
import edu.tamu.wala.newkobj.KObjectSensitiveContextSelector;
import edu.tamu.wala.newkobj.ReceiverString;
import edu.tamu.wala.newkobj.ReceiverStringContext;

public class KObjIPABuilder extends AbstractKIPASSAPropagationCallGraphBuilder {
	
	/**
	 * @param n -> k
	 * @param fakeRootMethod
	 * @param options
	 * @param cache
	 * @param appContextSelector
	 * @param appContextInterpreter
	 */
	public KObjIPABuilder(int n, AbstractRootMethod fakeRootMethod, AnalysisOptions options,
			IAnalysisCacheView cache, ContextSelector appContextSelector, SSAContextInterpreter appContextInterpreter) {
	    super(fakeRootMethod, options, cache, (IPAPointerKeyFactory) new IPADefaultPointerKeyFactory());
	    if (options == null) {
	      throw new IllegalArgumentException("options is null");
	    }

	    ContextSelector def = new DefaultContextSelector(options, cha);
	    ContextSelector contextSelector = appContextSelector == null ? def : new DelegatingContextSelector(appContextSelector, def);
	    contextSelector = new KObjectSensitiveContextSelector(n, contextSelector);
	    setContextSelector(contextSelector);

	    SSAContextInterpreter defI = new DefaultSSAInterpreter(options, cache);
	    defI = new DelegatingSSAContextInterpreter(ReflectionContextInterpreter.createReflectionContextInterpreter(cha, options, getAnalysisCache()), defI);
	    SSAContextInterpreter contextInterpreter = appContextInterpreter == null ? defI : new DelegatingSSAContextInterpreter(appContextInterpreter, defI);
	    setContextInterpreter(contextInterpreter);
	}
	
	@Override
	public IRChangedSummary updateCallGraph(IRChangedSummary summary) {
		System.out.println("Implement updateCallGraph in kobj");
		return summary;
	}
	
	
	
	@Override
	public IRChangedSummary updatePointerAnalysis(IRChangedSummary summary) throws CancelException {
		assert system.kobj == true;
		setChange(true);//double make sure 

		HashMap<MethodReference, ArrayList<CGNode>> m2cgnodes = summary.getChangedMethod2CGNodes();
		HashMap<MethodReference, IRDiff> m2IRDiff = summary.getChangedMethod2IRDiff();
		
		long update1_start = System.currentTimeMillis();
		System.out.println("PTA UPDATING: (1) PRECOMPUTING INVALID ELEMENTS ... ");
		for(MethodReference mRef : m2cgnodes.keySet()) {
			IRDiff irDiff = m2IRDiff.get(mRef);
			if(irDiff.hasDeletedInvokeAndNew()) {
				ArrayList<CGNode> mBelongs = m2cgnodes.get(mRef);
				ArrayList<SSAAbstractInvokeInstruction> delete_invokes = irDiff.getDeleteInvokes();
				ArrayList<SSANewInstruction> delete_news = irDiff.getDeletedNews();

				//apply S𝑐𝑡𝑥-Rule on news
				applySctxRuleKObj(delete_news, mBelongs);

				//apply 𝑆𝑖𝑛𝑣-Rule on invokes
				applySinvRuleKObj(delete_invokes, mBelongs);
			}
		}
		
		setOPT(true);
		//handle invalid cgnode
		deleteInvalidCGNode();
		
		// check invalidity
		moreDeletion();
		
		//handle invalid cgnode again
		deleteInvalidCGNode();
		setOPT(false);
		
		total_invalids = invalidCGNodes.size();
		
		summary.total_opt = (System.currentTimeMillis() - update1_start);
		
		System.out.println("PTA UPDATING: (2) PROPAGATE ... ");
		super.updatePointerAnalysis(summary);
		return summary;
	}


	/**
	 * apply S𝑐𝑡𝑥-Rule on news
	 * @param delete_news
	 * @param mBelongs
	 */
	private void applySctxRuleKObj(ArrayList<SSANewInstruction> delete_news, ArrayList<CGNode> mBelongs) {
		//mBelongs: this requires to del this new related constraints; this should be covered when we propagate constraints in super ...
		
		//iterative discover descendents of delete news 
		for(SSANewInstruction delete_new : delete_news) {
			NewSiteReference delete_newsite = delete_new.getNewSite(); //newsite in delete context
			for(CGNode mBelong : mBelongs) {
				IMethod method = mBelong.getMethod(); //imethod in deleted context, together with the above newsite compose the deleted context

				//discover direct invokes by deleted news
				InstanceKey del_obj = getInstanceKeyForAllocation(mBelong, delete_newsite); //this should always return the same instance
				int del_base_idx = system.getInstanceIndex(del_obj);  
				HashSet<Integer> direct_target_ids = baseInstance2Targets.get(del_base_idx);
				if (direct_target_ids == null) 
					continue;
				
				for(Integer direct_target_id : direct_target_ids) {
					CGNode direct_target = callGraph.getNode(direct_target_id); //this is invalid
					
//					ContextItem ctx = direct_target.getContext();
//					if(ctx instanceof JavaTypeContext) {
//						continue; // skip this change, since this involves too many cgnodes and constraints
//					}
					
					total_Sctx++;

					if(DEBUG_K) {
						System.out.println("Sctx (KObj) invalid: " + direct_target);
					}
					
					updateInvalidCGNodesAndRemoveIncomingEdges(direct_target); //do we double check its contexts? 
					
					//traverse its desendents collect any deleted context exist
					Iterator<CGNode> iterator = callGraph.getSuccNodes(direct_target);
					while(iterator.hasNext()) {
						CGNode target = iterator.next();
						iterativelyDetermineInvalidUpdateCGNodes(delete_newsite, method, target);
					}
				}
				//update
				baseInstance2Targets.remove(del_base_idx);
			}
		}
	}

	/**
	 * @param delete_newsite
	 * @param method
	 * @param target
	 */
	private void iterativelyDetermineInvalidUpdateCGNodes(NewSiteReference delete_newsite, IMethod method, CGNode target) {
		Set<CGNode> checks = new HashSet<>(); //this iteration
		Set<CGNode> tmp = new HashSet<>(); //next iteration
		Set<CGNode> moreDel = new HashSet<>(); // send to MoreDel later
		checks.add(target);

		while(!checks.isEmpty()) {
			for(CGNode check : checks) { //check if deleted context is there
				ContextItem ctx = check.getContext();
				if(ctx instanceof ReceiverStringContext) {
					ReceiverStringContext rctx = (ReceiverStringContext) ctx;
					ReceiverString receiverString = (ReceiverString) rctx.get(KObjectSensitiveContextSelector.RECEIVER_STRING);
					if(receiverString.includes(delete_newsite, method)) { //check if this is invalid
						boolean isNew = updateInvalidCGNodesAndRemoveIncomingEdges(check);
						if(isNew) { //avoid dup
							tmp.addAll(((IPAExplicitNode) check).getAllTargets());
						}
					}else {
						//this reaches MoreDel: further check if target still has other callers except the ones in invalidCGNodes
						moreDel.add(check);
					}
				}
				else if(ctx instanceof JavaTypeContext) {
					moreDel.add(check);
				}
				else if(ctx == null || ctx instanceof DelegatingContext) { //Everywhere has null ContextItem  
					System.out.println("let me know: DelegatingContext");
				} else {
					throw new IllegalStateException("ContextItem: " + ctx.toString() + " should not be shown in applySctxRuleKCFA");
				}
			}
			//update
			checks.clear();
			checks.addAll(tmp);
			tmp.clear();
		}
		
		updatePossibleDeleteCGNodes(moreDel);
	}

	/**
	 * apply 𝑆𝑖𝑛𝑣-Rule on invokes
	 */
	private void applySinvRuleKObj(ArrayList<SSAAbstractInvokeInstruction> delete_invokes, ArrayList<CGNode> mBelongs) {
		//mBelongs: this requires to del this invoke related constraints; this should be covered when we propagate constraints in super ... 

		//iterate discover callee of delete invokes
		Set<CGNode> moreDel = new HashSet<>(); // send to MoreDel later
		for(SSAAbstractInvokeInstruction delete_invoke : delete_invokes) {
			CallSiteReference del_callsite = delete_invoke.getCallSite(); //invalid cs
			for(CGNode mBelong : mBelongs) {
				Set<CGNode> targets = callGraph.getPossibleTargets(mBelong, del_callsite);
				for(CGNode target : targets) { //this is invalid
//					ContextItem ctx = target.getContext();
//					if(ctx instanceof JavaTypeContext) {
//						continue; // skip this change, since this involves too many cgnodes and constraints
//					}
					
					if(DEBUG_K) {
						System.out.println("Sinv (KObj) invalid: " + target);
					}
					total_Sinv++;
					updateInvalidCGNodesAndRemoveIncomingEdges(target);
					
					//check if its descendents are still valid
					moreDel.add(target);
				}
			}
		}
		updatePossibleDeleteCGNodes(moreDel);
	}


	
	
	
	
	
	
	
	
	
	
	

}
