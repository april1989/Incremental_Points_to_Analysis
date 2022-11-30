package edu.tamu.wala.increpta.change;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import org.apache.commons.collections4.IteratorUtils;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSANewInstruction;

import edu.tamu.wala.increpta.callgraph.impl.IPAExplicitCallGraph.IPAExplicitNode;

import com.ibm.wala.ssa.SSACFG.BasicBlock;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;




public class IRDiff {
	
	private static boolean DEBUG = false;
	
	/**
	 * used by S𝑐𝑡𝑥-Rule and 𝑆𝑖𝑛𝑣-Rule: to avoid redo the ir traversal, 
	 * check if ir_old has deleted invoke and new stmt  
	 */
	public static boolean CHECK_NEW_AND_INVOKE = true;
	//for the above use
	private ArrayList<SSAAbstractInvokeInstruction> delete_invokes = new ArrayList<>();
	private ArrayList<SSANewInstruction> deleted_news = new ArrayList<>();
	
	//input 
	private CGNode node_old; // used to check whether we need to redo thread creation, since this information is stored in cgnode
	private IR ir_old;
	private IR ir_new;
	ArrayList<AstChangedItem> furItems;
	private IMethod method_old; 
	private IMethod method_new;
	private boolean annotation;

	//instead of Map<SSAInstruction, BasicBlock>, use basicblock, since instructions 
	//BB16<Handler> (<Primordial,Ljava/lang/Throwable>)
    //   v23 = getCaughtException
	//will be ignored here 
	private ArrayList<BasicBlock> delete_bbs = new ArrayList<>();//removed from node_old
	private ArrayList<BasicBlock> add_bbs = new ArrayList<>();//added by node_new

	
	//!! all the diff_idx_* are the same in cgnode_olds; 
	//we want to skip this when they have multiple contexts.
	private int diff_idx_old = -1;//changes are started from which idx in old_insts
	private int diff_idx_new = -1;//changes are started from which idx in new_insts

	private boolean is_total_remove = false;
	private boolean is_total_add = false;

	/**
	 * -1: node has no loop/threadnode; 
	 * 0: has but no need to recreate;
	 * 1: has and need to recreate...
	 * !!!!!!!!! currently assume 1 node only can have one threadnode or loopnode; TODO: extend....
	 */
	private int recreate_thread = -1;
	private List<SSAInstruction> thread_def_insts_old = new ArrayList<>();//thread-related statements...
	private List<SSAInstruction> thread_def_insts_new = new ArrayList<>();
	
	/**
	 * only for annotation: to record the changes in caller CGNodes by annotaitons 
	 * caller <-> [diff_start_idx_annotation, diff_end_idx_annotation]
	 */
	private HashMap<CGNode, ArrayList<Integer>> invokeChangesByAnnotation = new HashMap<>();
	

	
	/**
	 * 1 for each IR, cgnode is a reference for thread creation -> thread-sensitive only
	 * for annotation changes, skip compute the diff_idx
	 * @param cgnode_old
	 * @param ir_old2
	 * @param ir_new2
	 * @param furItems2
	 * @param annotation
 	 */
	public IRDiff(CGNode node_old, IR ir_old, IR ir_new, IMethod m_old, IMethod m_new, 
			ArrayList<AstChangedItem> furItems, boolean annotation, IRDiff referenceDiff) {
		this.node_old = node_old;
		this.ir_new = ir_new;
		this.ir_old = ir_old;
		this.method_old = m_old;
		this.method_new = m_new;
		this.furItems = furItems;
		this.annotation = annotation;
		if(annotation) {
			this.diff_idx_old = 0;
			this.diff_idx_new = 0;
		} else if(referenceDiff != null) {
			//skip compareIRs, since the diff are the same as referenceDiff
			this.diff_idx_old = referenceDiff.getDiffIndexOld();
			this.diff_idx_new = referenceDiff.getDiffIndexNew();
			if(referenceDiff.skipRecreateThread()) {
				System.out.println("SKIP RECREATE THREADs IN THIS NODE: " + node_old.toString());
				this.recreate_thread = 0;
				this.thread_def_insts_new = referenceDiff.getThreadDefInstsNew();
				this.thread_def_insts_old = referenceDiff.getThreadDefInstsOld();
//				summarizeDiff();
			}
		}else {
			compareIRs();
			
			if(CHECK_NEW_AND_INVOKE) {
				collectDeletedNewsAndInvokes();
			}

			IPAExplicitNode explicit = (IPAExplicitNode) node_old;
			if(DEBUG) {
				System.out.println("----------------IR Old----------------\n" + ir_old.toString());
				System.out.println("----------------IR New----------------\n" + ir_new.toString());
			}
		}
		summarizeDiff();
	}
	
	@Override
	public String toString() {
		return "IR SUM: " + node_old.toString();
	}
	
	public IR getNewIR() {
		return ir_new;
	}
	
	public IR getOldIR() {
		return ir_old;
	}
	
	public IMethod getNewMethod() {
		return method_new;
	}
	
	public IMethod getOldMethod() {
		return method_old;
	}
	
	public boolean doRecreateThread() {
		return recreate_thread == 1;
	}
	
	public boolean skipRecreateThread() {
		return recreate_thread == 0;
	}
	
	public boolean hasFurItems() {
		return furItems == null? false : !furItems.isEmpty();
	}
	
	/**
	 * both == -1 means no change...
	 * @return
	 */
	public boolean hasAnyIRDiff() {
		return diff_idx_old != -1 && diff_idx_new != -1;
	}

	/**
	 * -1: the same insts; others are idx
	 * @return changes are started from which idx in old_insts
	 */
	public int getDiffIndexOld() {
		return diff_idx_old;
	}
	
	/**
	 * -1: the same insts; others are idx
	 * @return changes are started from which idx in new_insts
	 */
	public int getDiffIndexNew() {
		return diff_idx_new;
	}
	
	public ArrayList<AstChangedItem> getFurItems() {
		return furItems;
	}
	
	/**
	 * @return added insts from ir_new
	 */
	public ArrayList<BasicBlock> getAddBasicBlocks() {
		return add_bbs;
	}
	
	/*
	 * @return the size of added ir instructions in add_bbs
	 */
	public int getSizeOfAddedIRs() {
		int s = 0;
		for(BasicBlock bb : add_bbs) {
			s += bb.getAllInstructions().size();
		}
		return s;
	}

	/**
	 * @return deleted insts from ir_old, cgnode_old 
	 */
	public ArrayList<BasicBlock> getDeleteBasicBlocks() {
		return delete_bbs;
	}
	
	/*
	 * @return the size of deleted ir instructions in delete_bbs
	 */
	public int getSizeOfDeletedIRs() {
		int s = 0;
		for(BasicBlock bb : delete_bbs) {
			s += bb.getAllInstructions().size();
		}
		return s;
	}
	
	
	public CGNode getCGNodeOld() {
		return node_old;
	}
	
	public List<SSAInstruction> getThreadDefInstsNew() {
		return thread_def_insts_new;
	}
	
	public List<SSAInstruction> getThreadDefInstsOld() {
		return thread_def_insts_old;
	}
	
	public HashMap<CGNode, ArrayList<Integer>> getInvokeChangesByAnnotation() {
		return invokeChangesByAnnotation;
	}
	
	public boolean isTotalRemove() {
		return is_total_remove && !is_total_add;
	}
	
	public boolean isTotalAdd() {
		return !is_total_remove && is_total_add;
	}
	
	public boolean isAnnotation() {
		return is_total_remove && is_total_add;
	}
	
	/**
	 * used by S𝑐𝑡𝑥-Rule and 𝑆𝑖𝑛𝑣-Rule
	 * @return
	 */
	public boolean hasDeletedInvokeAndNew() {
		return !delete_invokes.isEmpty() || !deleted_news.isEmpty();
	}
	
	public ArrayList<SSAAbstractInvokeInstruction> getDeleteInvokes() {
		return delete_invokes;
	}
	
	public ArrayList<SSANewInstruction> getDeletedNews() {
		return deleted_news;
	}
	
	/**
	 * compute delete_insts and add_insts
	 */
	private void summarizeDiff() {
		if(diff_idx_old == -1)
			return;
		if(diff_idx_old == 0 && diff_idx_new == 0) {//the first two cases only happens when adding an new invoke with a new target method
			if(ir_new == null && ir_old != null) {
				//this node has been removed...
		        doDelete(0);
		        is_total_remove = true; 
			}
			if(ir_old == null && ir_new != null){
				//this node is totally newly added ...
				doAdd(0);
				is_total_add = true;
			}
			if(ir_old != null && ir_new != null) {
				if(annotation) {
					//annotation change
					doDelete(0);
//					doAdd(0);// no need to
					is_total_remove = true; is_total_add = true;
				}else {
					doDelete(0);
					doAdd(0);
				}
			}
			return;
		}
		//diff_idx > 0 =>  is_total_remove = false; is_total_add = false;
		doDelete(diff_idx_old);
		doAdd(diff_idx_new);
	}


	private void doAdd(int idx) {
		SSAInstruction[] insts_new = ir_new.getInstructions();
		SSACFG cfg_new = ir_new.getControlFlowGraph();
		for (int i = idx; i < insts_new.length; i++) {
			SSAInstruction inst = insts_new[i];
			if(inst != null) {
				if(recreate_thread == 0) {
					if(thread_def_insts_new.contains(inst))
						continue;
				}
				BasicBlock bb = cfg_new.getBlockForInstruction(inst.iindex);
				add_bbs.add(bb);
			}
		}
	}

	private void doDelete(int idx) {
		SSAInstruction[] insts_old = ir_old.getInstructions();
		SSACFG cfg_old = ir_old.getControlFlowGraph();
		for (int i = idx; i < insts_old.length; i++) {
			SSAInstruction inst = insts_old[i];
			if(inst != null) {
				if(recreate_thread == 0) {
					if(thread_def_insts_old.contains(inst))
						continue;
				}
				BasicBlock bb = cfg_old.getBlockForInstruction(inst.iindex);
				delete_bbs.add(bb);
			}
		}
	}

	/**
	 * for k-sensitive only
	 */
	private void collectDeletedNewsAndInvokes() {
		if(diff_idx_old == -1)
			return;
		
		SSAInstruction[] insts_old = ir_old.getInstructions();
		for (int i = diff_idx_old; i < insts_old.length; i++) {
			SSAInstruction inst = insts_old[i];
			if(inst == null)
				continue;
			
			if(inst instanceof SSAAbstractInvokeInstruction) { 
				delete_invokes.add((SSAAbstractInvokeInstruction) inst);
			}else if(inst instanceof SSANewInstruction) { 
				deleted_news.add((SSANewInstruction) inst);
			}
		}
	}

	/**
	 * compare old ir with new ir:
	 * store the index of the first different ir statment from ir_old!!!! ; -1 if no difference
	 */
	private void compareIRs() {
		if(ir_old == null || ir_new == null) {
			//the first one is different...
			diff_idx_new = 0;
			diff_idx_old = 0;
			return;
		}
		SSAInstruction[] insts_old = ir_old.getInstructions();
		SSAInstruction[] insts_new = ir_new.getInstructions();
		int j = 0; // j == idx of insts_new
		int i = 0; // i == idx of insts_old
		int min_length = Math.min(insts_new.length, insts_old.length);
		while (i < min_length) {
			SSAInstruction oldInst = insts_old[i];
			SSAInstruction newInst = insts_new[j];	
			if(oldInst == null && newInst == null){
				i++; j++;
				continue;
			}else if(oldInst != null && newInst != null){
				if(oldInst.toString().equals(newInst.toString())){
					//corner case for: new Thread(new Runnable(){ ... })
					//changes are not in this ir, should be in hidden new body,
					if(newInst instanceof SSAAbstractInvokeInstruction){
						SSAAbstractInvokeInstruction invoke = (SSAAbstractInvokeInstruction) newInst;
						String cname = invoke.getCallSite().getDeclaredTarget().getDeclaringClass().getName().getClassName().toString();
						if(cname.contains("anonymous subclass of java.lang.Object")){
							AstChangedItem item = new AstChangedItem();
							item.packageName = invoke.getCallSite().getDeclaredTarget().getDeclaringClass().getName().getPackage().toString().replace('/', '.');
							item.methodName = "run";
							item.className = cname;
							if(!furItems.contains(item))
								furItems.add(item);
						}
					}
					
					i++; j++;
					continue;
				}else{
					// exclude some super unimportant cases
					if(oldInst instanceof SSAConditionalBranchInstruction && newInst instanceof SSAConditionalBranchInstruction) {
						i++; j++;
						continue;
					}
					
					diff_idx_new = j;
					diff_idx_old = i;
					return;
				}
			}else if(oldInst != null && newInst == null) {
				j++;
			}else {//oldInst == null && newInst != null
				i++; 
			}
		}
		
		//all the same
		diff_idx_new = -1;
		diff_idx_old = -1;
		return; 
	}
	
	
	
	/* there are different cases here:
	 * THE KEY IS TO CHECK WHETHER THIS DELETION AFFECTS THE THREAD.RUN() BODY.
	 *  Now, assume if thread initialization and start statement not change, thread will not change....
	 *  And, if changes are after thread-related statements, thread will not change... 
	 *  TODO: a better way is to compare pts of new and old for the thread-related statements....
	 */
	private void checkTheBodyOfThread(IPAExplicitNode explicit) {//explicit = cgnode_old
		if(ir_old == null || ir_new == null) {
			recreate_thread = 1;
			return;
		}
	}
	
	private void collectThreadDefInstOld(IPAExplicitNode explicit, int def) {
		DefUse du_old = explicit.getDU();
		SSAInstruction t_def_old = du_old.getDef(def);
		thread_def_insts_old.add(t_def_old);
		Iterator<SSAInstruction> iter_old = du_old.getUses(def);
		while (iter_old.hasNext()) {
			SSAInstruction inst = (SSAInstruction) iter_old.next();
			thread_def_insts_old.add(inst);
		}
	}
	
	private void collectThreadDefInstNew(int def) {
		DefUse du_new = new DefUse(ir_new); 
		SSAInstruction t_def_new = du_new.getDef(def);
		thread_def_insts_new.add(t_def_new);
		Iterator<SSAInstruction> iter_new = du_new.getUses(def);
		while (iter_new.hasNext()) {
			SSAInstruction inst = (SSAInstruction) iter_new.next();
			thread_def_insts_new.add(inst);
		}
	}
	
	/**
	 * 
	 * @param explicit
	 * @param def -> value number of thread instance
	 * TODO: bz: 
	 * 1. what counts as change? def change? 
	 * 2. for the invokes (not only invokespecial, but also different types of constructor-related invokes), does program counter change count as change? 
	 * 3. how to handle thread join ?? should not included here
	 */
	private void compareThreadStatement(IPAExplicitNode explicit, int def) {
		collectThreadDefInstOld(explicit, def);
		collectThreadDefInstNew(def);
		
		if(thread_def_insts_new.size() == thread_def_insts_old.size()) {
			for (int i = 0; i < thread_def_insts_new.size(); i++) {
				SSAInstruction def_new = thread_def_insts_new.get(i);
				SSAInstruction def_old = thread_def_insts_old.get(i);
				if(!def_new.toString().equals(def_old.toString())) {
					if(def_new instanceof SSAAbstractInvokeInstruction && def_old instanceof SSAAbstractInvokeInstruction) {
						if(((SSAAbstractInvokeInstruction) def_new).getCallSite().getDeclaredTarget().getName().toString().equals("join")
								&& ((SSAAbstractInvokeInstruction) def_old).getCallSite().getDeclaredTarget().getName().toString().equals("join")) {
							//this will not affect the thread instance, skip
							continue;
						}
					}
					recreate_thread = 1;
					return;
				}
			}
		}else { //create different number of threads in old and new irs
			recreate_thread = 1;
			return;
		}
		
		//change is before the thread-related statements, but the statements are not affected ... 
		// e.g., refactering variables
		// may need a strict check procedure
//		if(invoke.toString().contains("invokevirtual < Source, Ljava/lang/Thread, start()V >")) {
//			//this is the last statement of thread-related statement, if this is the same; then no changes in thread ...
//		}
		
	}

	
	/**
	 * only for annotation: to record the changes in caller CGNodes by annotaitons 
	 * @param exisit_caller_invoke
	 */
	public void updateCallerChangesByAnnotation(HashMap<CGNode, HashMap<SSAInstruction, BasicBlock>> exisit_caller_invoke) {
		for (CGNode caller : exisit_caller_invoke.keySet()) {
			SSAInstruction[] insts = caller.getIR().getInstructions();
			Object[] invoke_insts = exisit_caller_invoke.get(caller).keySet().toArray();//this is stored in the reversed order
			//assume the idx of invoke_insts are continuous
			ArrayList<Integer> idx = new ArrayList<>();
			for (int i = 0; i < insts.length; i++) {
				SSAInstruction inst = insts[i];
				if(inst != null) {
					if(inst.toString().equals(invoke_insts[invoke_insts.length - 1].toString())) {
						//found 1st stmt 
						idx.add(i);
						break;
					}
//					//TODO: for annotated constructor
//					if(inst.toString().equals(invoke_insts[0].toString())) {
//						//found last stmt 
//						idx.add(i);
//						break;
//					}
				}
			}
//			if(idx.isEmpty() || idx.size() == 1) {//TODO: for annotated constructor
			if(idx.isEmpty()) {
				System.err.println("DID NOT FIND ALL INVOKE STMTS FOR " + caller.toString());
				continue;
			}
			invokeChangesByAnnotation.put(caller, idx);
		}
	}
	
	
	
}
