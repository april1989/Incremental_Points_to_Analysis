package edu.tamu.wala.increpta.scc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationGraph;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationSystem;

public class SCCEngine {

	private IPAPropagationGraph flowGraph;
	private AdjacencyLists adjGraph;

	//maintain a mapping of existing sccs <-> ptsv ids:
	//3 maps maintained together
	private HashMap<Integer, Integer> vid_sccid = new HashMap<>();
	private HashMap<Integer, HashSet<Integer>> sccid_vid = new HashMap<>();
	private HashMap<Integer, SCCVariable> sccid_sccV = new HashMap<>();

	//for fake pointerKey
	private static String FAKENAME = "fake pointer key";
	private int pid = 0;

	/**
	 * only   public boolean newConstraint(PointerKey lhs, UnaryOperator<PointsToSetVariable> op, PointerKey rhs) {
	 * and   public boolean delConstraint(PointerKey lhs, IPAUnaryOperator<PointsToSetVariable> op, PointerKey rhs) {
	 * indicate the implicitly relation : assign/filter
	 */
	public SCCEngine(IPAPropagationSystem system, IPAPropagationGraph flowGraph) {
		this.flowGraph = flowGraph;
		adjGraph = new AdjacencyLists(flowGraph);
		initializeGraph();
	}

	private void initializeGraph() {
		adjGraph.initialize(flowGraph);
	}

	/**
	 * for the whole program
	 */
	public void initialSCCDetection(){
		ArrayList<HashSet<Integer>> sccs = adjGraph.initialSCC();
		adjGraph.printAll();
		//find corresponding stmts for sccs
		for (HashSet<Integer> scc : sccs) {
			//initialize scc variable
			SCCVariable sccV = findOrCreateSCCs(scc);
			//update maps
			maintain3Maps(scc, sccV);
		}
	}


	/**
	 * find existing or create SCC for a scc
	 * @param scc
	 */
	private SCCVariable findOrCreateSCCs(HashSet<Integer> scc) {
		Object[] array = scc.toArray();
		SCCVariable sccV = sccid_sccV.get(vid_sccid.get(((int)array[0])));
		if(sccV == null){
			//create and initialize scc variable
			SSCPointerKey pKey = getFakePointerKey();
			sccV = new SCCVariable(pKey, scc);
			sccV.fillInVariables(flowGraph);
		}
		return sccV;
	}

	private SSCPointerKey getFakePointerKey(){
		pid++;
		return new SSCPointerKey(FAKENAME + pid);
	}

	/**
	 * + maintain a mapping of existing sccs <-> ptsv ids:
	 * 3 maps together
	 * @param scc
	 * @param sccV
	 */
	private void maintain3Maps(HashSet<Integer> scc, SCCVariable sccV) {
		Integer hashcode = sccV.hashCode();//assume no duplicate hashcode
		sccid_sccV.put(hashcode, sccV);
		sccid_vid.put(hashcode, scc);
		for (Integer vid : scc) {
			vid_sccid.put(vid, hashcode);
		}
	}

	/**
	 * opposite to maintain3Maps
	 * @param scc
	 * @param sccV
	 */
	private void remove3Maps(HashSet<Integer> scc, SCCVariable sccV) {
		Integer hashcode = sccV.hashCode();//assume no duplicate hashcode
		sccid_sccV.remove(hashcode);
		sccid_vid.remove(hashcode);
		for (Integer vid : scc) {
			vid_sccid.remove(vid);
		}
	}

	/**
	 * incrementally ask, if a vid belongs to some sccs
	 * @param vid
	 * @return
	 */
	public boolean belongToSCC(int vid) {
		return vid_sccid.containsKey(vid);
	}

	/**
	 * if vid belongs to some sccs here, return all the vids that belongs to the same scc.
	 * for IPAPropagationGraph, getStatementsThatUse/Def
	 * @param vid
	 * @return
	 */
	public HashSet<Integer> getCorrespondingVIDsInSCC(int vid){
		return sccid_vid.get(vid_sccid.get(vid));
	}

	/**
	 * return the SCCVariable which vid belongs to
	 * in order to update its pts
	 * @param vid
	 * @return
	 */
	public SCCVariable getCorrespondingSCCVariable(int vid){
		return sccid_sccV.get(vid_sccid.get(vid));
	}

	/**
	 * start to incremental change the program
	 * @param change
	 */
	public void setChange(boolean change){
		adjGraph.setChange(change);
	}

	/**
	 * for incremental
	 * rhs -> lhs
	 * @param lhs
	 * @param rhs
	 * @param isFilter
	 * @return boolean
	 */
	public boolean addEdge(int lhs, int rhs, boolean isFilter){
		adjGraph.addEdge(rhs, lhs, isFilter);
		boolean has_new = adjGraph.hasNewSCCs();
		if(has_new){
			update3MapsForAddEdge(rhs);
		}
		return has_new;
	}

	/**
	 * for incremental
	 * rhs -> lhs
	 * @param lhs
	 * @param rhs
	 * @param isFilter
	 * @return boolean
	 */
	public boolean removeEdge(int lhs, int rhs, boolean isFilter){
		adjGraph.removeEdge(rhs, lhs, isFilter);
		boolean has_remove = adjGraph.hasRemovedSCCs();
		if(has_remove){
			update3MapsForDeleteEdge(rhs);
		}
		return has_remove;
	}

	/**
	 * remove no longer used v in graph
	 * @param v
	 */
	public void removeNode(int v){
		adjGraph.removeVertex(v);
		adjGraph.removeFilterVertex(v);
	}


	/**
	 * incrementally update the 3 maps for sccs <-> vids
	 * for add edges
	 * @param rhs
	 */
	private void update3MapsForAddEdge(int rhs){
		ArrayList<HashSet<Integer>> new_sccs = adjGraph.getNewSCCs();
		for (HashSet<Integer> scc : new_sccs) {
			boolean shortcut = false;
			//check if remove sccs might exist
			ArrayList<HashSet<Integer>> remove_sccs = adjGraph.getRemovedSCCs();
			for (HashSet<Integer> remove : remove_sccs) {
				if(scc.containsAll(remove)){
					shortcut = true;
					//reuse
					SCCVariable sccV = findOrCreateSCCs(remove);
					if(sccV == null){
						throw new RuntimeException("Cannot Find SCCVariable For An Existing SCC.");
					}
					//should keep the SCCVariable, just add the id
					HashSet<Integer> adds = new HashSet<>();
					adds.addAll(scc);
					adds.removeAll(remove);//scc only contains the add ids currently
					//update 3maps, just add the id
					Integer hashcode = sccV.hashCode();//assume no duplicate hashcode
					sccid_vid.get(hashcode).addAll(adds);
					for (Integer vid : adds) {
						vid_sccid.put(vid, hashcode);
					}
					//update sccV
					sccV.updateForAdd(adds, flowGraph, rhs);
				}
			}
			if(shortcut){
				continue;
			}
			SCCVariable sccV = findOrCreateSCCs(scc);
			maintain3Maps(scc, sccV);
		}
	}

	/**
	 * incrementally update the 3 maps for sccs <-> vids
	 * for remove edges
	 */
	private void update3MapsForDeleteEdge(int rhs){
		ArrayList<HashSet<Integer>> remove_sccs = adjGraph.getRemovedSCCs();
		for (HashSet<Integer> scc : remove_sccs) {
			SCCVariable sccV = findOrCreateSCCs(scc);
			if(sccV == null){
				throw new RuntimeException("Cannot Find SCCVariable For An Existing SCC.");
			}
			//check if inner sccs might exist
			boolean shortcut = false;
			ArrayList<HashSet<Integer>> inner_sccs = adjGraph.getInnerSCCs();
			for (HashSet<Integer> inner : inner_sccs) {
				if(scc.containsAll(inner)){
					shortcut = true;
					//should keep the SCCVariable, just remove the id
					HashSet<Integer> deletes = new HashSet<>();
					deletes.addAll(scc);
					deletes.removeAll(inner);//scc only contains the removed ids currently
					//update 3maps, just remove the id
					Integer hashcode = sccV.hashCode();//assume no duplicate hashcode
					sccid_vid.get(hashcode).removeAll(deletes);
					for (Integer vid : deletes) {
						vid_sccid.remove(vid);
					}
					//update sccV
					sccV.updateForDelete(deletes, flowGraph, rhs);
				}
			}
			if(shortcut){
				continue;
			}
			//remove it from all 3 maps
			remove3Maps(scc, sccV);
		}
	}


}
