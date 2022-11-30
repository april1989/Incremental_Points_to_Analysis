package edu.tamu.wala.increpta.change;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSACFG.BasicBlock;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.fixpoint.IVariable;

public class IRChangedSummary {

    /////////////////////////////computed changes from IRChangeAnalyzer; /////////////////////
	//// previously from converthandler/////////////////////////////
	/**
	 * IR changes (code only) are the same for CGNodes shared the same MethodReference: 
	 * mr2Nodes@IPABasicCallGraph
	 */
	private HashMap<MethodReference, ArrayList<CGNode>> changedMethod2CGNodes = new HashMap<>();
	private HashMap<MethodReference, IRDiff> changedMethod2IRDiff = new HashMap<>();
	
	/**
	 * only has source code changes, no other changes
	 */
	private HashMap<CGNode, IRDiff> changedCodeCGNodes = new HashMap<>();
	
	/**
	 * has sync modifier changes
	 * !! this may also includes source code changes and annotation changes
	 */
	private HashMap<CGNode, IRDiff> changedSyncCGNodes = new HashMap<>();
	
	/**
	 * has annotation changes; 
	 * !! this may also includes source code changes and sync modifier changes
	 */
	private HashMap<CGNode, IRDiff> changedAnnotationCGNodes = new HashMap<>();
	
	private HashMap<CGNode, ArrayList<AstChangedItem>> furItemMap = new HashMap<>();
	

	/////////////////////////////saved changes after incremental pag/cg ///////////////////
	
	/**
	 * removed cgnodes for this save; most are callees 
	 */
	private HashSet<CGNode> alreadyRemoveNodes = new HashSet<>();
	
	/**
	 * added cgnodes for this save; most are callees 
	 */
	private HashSet<CGNode> newlyDiscoveredNodes = new HashSet<>();
	
	/**
	 * ipapointstosetvariable with pts changes during deletion
	 */
	private HashSet<IVariable> deleteChangedV = new HashSet<>(); 
	
	/**
	 * ipapointstosetvariable with pts changes during addition
	 */
	private HashSet<IVariable> addChangedV = new HashSet<>(); 
	
	// total add/del irs for this summary
	public int total_inst_add = 0;
	public int total_inst_del = 0;
	public int total_del_new = 0;
	public int total_del_invoke = 0;

	// times
	public long total_opt = 0; // for k-sensitive optimization time only
	public long total_add = 0;
	public long total_del = 0;
	public long worst_add = 0;
	public long worst_del = 0;
	
	// record the worst performance inst
	public SSAInstruction worstAddInstruction;
	public SSAInstruction worstDelInstruction;

	
	/**
	 * one summary for one save/commit; may have many changed cgnodes
	 * 
	 * PS: for IR changes (code only) from the same MethodReference, we already considered the redundanct computation 
	 * to avoid redo ir change for different cgnodes
	 */
	public IRChangedSummary() {
	}
	

	public boolean onlySyncModifierChanges() {
		return !changedSyncCGNodes.isEmpty() && changedCodeCGNodes.isEmpty() && changedAnnotationCGNodes.isEmpty();
	}
	
	public boolean doUpdatePTA() {
		return !changedCodeCGNodes.isEmpty() || !changedSyncCGNodes.isEmpty() || !changedAnnotationCGNodes.isEmpty();
	}
	
	public boolean hasAnyChanges() {
		return !changedCodeCGNodes.isEmpty() || !changedSyncCGNodes.isEmpty()
				|| !deleteChangedV.isEmpty() || !addChangedV.isEmpty() || !changedAnnotationCGNodes.isEmpty();
	}
	
	/**
	 * pts has any changes.
	 * @return
	 */
	public boolean hasPTSChanges() {
		return !deleteChangedV.isEmpty() || !addChangedV.isEmpty(); 
	}
	
	public boolean hasNonAnnotationChanges() {
		return (!changedCodeCGNodes.isEmpty() || !changedSyncCGNodes.isEmpty()) 
				&& changedAnnotationCGNodes.isEmpty(); 
	}
	
	/**
	 * @param node
	 * @return can be null
	 */
	public ArrayList<AstChangedItem> getFurItem(CGNode node) {
		return furItemMap.get(node);
	}
	
	/**
	 * compute it online
	 * @return
	 */
	public HashMap<MethodReference, ArrayList<CGNode>> getChangedMethod2CGNodes() {
		for(CGNode node : changedCodeCGNodes.keySet()) {
			MethodReference method = node.getMethod().getReference();
			ArrayList<CGNode> exists = changedMethod2CGNodes.get(method);
			if(exists == null) {
				exists = new ArrayList<>();
				changedMethod2CGNodes.put(method, exists);
				
				//if this is new, it is also new in changedMethod2IRDiff -> store it
				changedMethod2IRDiff.put(method, changedCodeCGNodes.get(node));
			}
			exists.add(node);
		}
		
		return changedMethod2CGNodes;
	}
	
	public HashMap<MethodReference, IRDiff> getChangedMethod2IRDiff() {
		return changedMethod2IRDiff;
	}
	
	public HashMap<CGNode, IRDiff> getChangedSyncCGNodes() {
		return changedSyncCGNodes;
	}
	
	public HashMap<CGNode, IRDiff> getChangedCodeCGNodes() {
		return changedCodeCGNodes;
	}
	
	public HashMap<CGNode, IRDiff> getChangedAnnotationCGNodes() {
		return changedAnnotationCGNodes;
	}
	
	public HashSet<CGNode> getAlreadyRemoveNodes() {
		return alreadyRemoveNodes;
	}
	
	public HashSet<CGNode> getNewlyDiscoveredNodes() {
		return newlyDiscoveredNodes;
	}
	
	public HashSet<IVariable> getDeleteChangedV() {
		return deleteChangedV;
	}
	
	public HashSet<IVariable> getAddChangedV() {
		return addChangedV;
	}
	
	/**
	 * summarize all changes and stored here for one changed cgnode
	 * but NO ANNOTATION
	 * @param cgnode_old
	 * @param m_old
	 * @param m_new
	 * @param ir_old
	 * @param ir_new
	 * @param furItems
	 * @param check
	 */
	public void computeIRChanges(CGNode cgnode_old, IMethod m_old, IMethod m_new, IR ir_old, IR ir_new,
			ArrayList<AstChangedItem> furItems, boolean check) {
		computeIRChanges(cgnode_old, m_old, m_new, ir_old, ir_new, false, furItems, check);
	}
	
	/**
	 * summarize all changes and stored here for one changed cgnode
	 * for analyzing source code 
	 * @param cgnode_old
	 * @param m_old
	 * @param m
	 * @param ir_old
	 * @param changedItem 
	 * @param ir
	 * @param furItems : anonymous thread creation
	 */
	public void computeIRChanges(CGNode cgnode_old, IMethod m_old, IMethod m_new, IR ir_old, IR ir_new,
			AstChangedItem changedItem, ArrayList<AstChangedItem> furItems, boolean check) {
		boolean annotation = (changedItem.annotationChange != -1);
		computeIRChanges(cgnode_old, m_old, m_new, ir_old, ir_new, annotation, furItems, check);
	}
	
	private void computeIRChanges(CGNode cgnode_old, IMethod m_old, IMethod m_new, IR ir_old, IR ir_new,
			boolean annotation, ArrayList<AstChangedItem> furItems, boolean check) {
		if(cgnode_old == null)
			System.out.println("HANDLE NULL CGNODE ..... ");

		IRDiff existDiff = null;
		if(check) {
			//!! all the diff_idx_* are the same in cgnode_olds; 
			//we want to skip this when they have multiple contexts.
			IMethod method = cgnode_old.getMethod();
			for (CGNode existnode : changedCodeCGNodes.keySet()) {
				IMethod existmethod = existnode.getMethod();
				if(method.equals(existmethod)) {//??  or string compare?? 
					existDiff = changedCodeCGNodes.get(existnode);
					break;
				}
			}
		}
		
		if(annotation) {
			//change starts from its callers to all its callees
			//changes in cgnode_old == delete cgnode_old and add a new cgnode
			IRDiff annotation_diff = new IRDiff(cgnode_old, ir_old, ir_new, m_old, m_new, furItems, annotation, existDiff);
			furItemMap.put(cgnode_old, annotation_diff.getFurItems());
			changedAnnotationCGNodes.put(cgnode_old, annotation_diff);
			return;// do not need to compute ir changes again...
		}
		
		//if exist, we just copy everything
		IRDiff diff = new IRDiff(cgnode_old, ir_old, ir_new, m_old, m_new, furItems, annotation, existDiff);
		
		if(diff.hasFurItems()) {
			furItemMap.put(cgnode_old, furItems);
		}
		if(diff.hasAnyIRDiff()) {
			furItemMap.put(cgnode_old, furItems);//? is this necessary?
			
			// update statistics only if this is a new method, not for each cgnode
			boolean doUpdate = true;
			for(CGNode cgnode : changedCodeCGNodes.keySet()) {
				IMethod m = cgnode.getMethod();
				if(m.equals(m_old) || m.equals(m_new)) {
					doUpdate = false;
				}
			}
			if(doUpdate) {
				total_inst_add += diff.getSizeOfAddedIRs();
				total_inst_del += diff.getSizeOfDeletedIRs();
				total_del_new += diff.getDeletedNews().size();
				total_del_invoke += diff.getDeleteInvokes().size();
			}
			
			// put to map
			changedCodeCGNodes.put(cgnode_old, diff);
		}
		
		if(m_new.isSynchronized() != m_old.isSynchronized()){
			//only sync modifer changes
			changedSyncCGNodes.put(cgnode_old, diff);
			
			// update statistics TODO: how?
		}
	}
	
	public void updateRemovedCGNodes(Set<CGNode> alreadyRemoved) {
		this.alreadyRemoveNodes.addAll(alreadyRemoved);
	}

	public void updateAddedCGNodes(Set<CGNode> newlyDiscoveredNodes) {
		this.newlyDiscoveredNodes.addAll(newlyDiscoveredNodes);
	}

	public void updateDeletedChangedV(HashSet<IVariable> changes) {
		this.deleteChangedV.addAll(changes);
	}

	public void updateAddedChangedV(HashSet<IVariable> changes) {
		this.addChangedV.addAll(changes);
	}
	
	public void printSummary() {
		System.out.println("++++++++++++++++++++++ Change Summary ++++++++++++++++++++++++++++++++++++++++");
		System.out.println("Source Code Changes: " + changedCodeCGNodes.size());
		for (CGNode cgNode : changedCodeCGNodes.keySet()) {
			System.out.println(cgNode.toString());
		}
		System.out.println("\nSync Modifier Changes: " + changedSyncCGNodes.size());
		for (CGNode cgNode : changedSyncCGNodes.keySet()) {
			System.out.println(cgNode.toString());
		}
		System.out.println("\nAnnotation Changes: " + changedAnnotationCGNodes.size());
		for (CGNode cgNode : changedAnnotationCGNodes.keySet()) {
			System.out.println(cgNode.toString());
		}
		System.out.println("\nNewly Added CGNodes: " + newlyDiscoveredNodes.size());
		for (CGNode cgNode : newlyDiscoveredNodes) {
			System.out.println(cgNode.toString());
		}
		System.out.println("\nNewly Removed CGNodes: " + alreadyRemoveNodes.size());
		for (CGNode cgNode : alreadyRemoveNodes) {
			System.out.println(cgNode.toString());
		}
		System.out.println("\nChanged PTS: add " + addChangedV.size() + "   del " + deleteChangedV.size());
		System.out.println("++++++++++++++++++++++ end of summary ++++++++++++++++++++++++++++++++++++++++\n");
	}



}
