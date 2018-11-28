package edu.tamu.wala.increpta.scc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Stack;

import com.ibm.wala.util.graph.INodeWithNumber;

import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPointsToSetVariable;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationGraph;
import edu.tamu.wala.increpta.operators.IPAAbstractOperator;
import edu.tamu.wala.increpta.operators.IPAAbstractStatement;
import edu.tamu.wala.increpta.operators.IPAUnaryStatement;

public class AdjacencyLists {

	boolean showdetails = false;
	boolean change = false;

	private HashMap<Integer, ArrayList<Integer>> adjListsMap;
	private boolean[] visited;
	private Stack<Integer> stack;
	private int time;
	private int[] lowlink;
	private int max_id;

	private IPAPropagationGraph flowGraph;

	private ArrayList<HashSet<Integer>> sccs;
	private ArrayList<HashSet<Integer>> new_sccs;
	private ArrayList<HashSet<Integer>> removed_sccs;
	private ArrayList<HashSet<Integer>> inner_sccs;

  

	public AdjacencyLists() {
		adjListsMap = new HashMap<>();
	}

	public AdjacencyLists(IPAPropagationGraph flowGraph) {
		this.flowGraph = flowGraph;
		adjListsMap = new HashMap<>();
	}

	public void setChange(boolean change) {
		this.change = change;
	}

	/**
	 * assume has new, then may have removed smaller sccs
	 * @return
	 */
	public boolean hasNewSCCs(){
		return new_sccs.size() != 0;
	}

	/**
	 * assume has remove, then may have added inner sccs
	 * @return
	 */
	public boolean hasRemovedSCCs(){
		return removed_sccs.size() != 0;
	}

	public boolean hasInnerSCCs(){
		return inner_sccs.size() != 0;
	}

	public ArrayList<HashSet<Integer>> getNewSCCs() {
		return new_sccs;
	}

	public ArrayList<HashSet<Integer>> getRemovedSCCs() {
		return removed_sccs;
	}

	public ArrayList<HashSet<Integer>> getInnerSCCs() {
		return inner_sccs;
	}

	@SuppressWarnings("rawtypes")
	public void initialize(IPAPropagationGraph flowGraph){
		Iterator<IPAPointsToSetVariable> iterator = flowGraph.getVariables();
		while (iterator.hasNext()) {
			IPAPointsToSetVariable v = (IPAPointsToSetVariable) iterator.next();//should be rhs
			Integer v_id = v.getGraphNodeId();
			ArrayList<IPAAbstractStatement> stmts = flowGraph.getImplicitStatementsThatUse(v);
			for (IPAAbstractStatement stmt : stmts) {
				if(stmt instanceof IPAUnaryStatement){
					IPAUnaryStatement implicitStmt = (IPAUnaryStatement) stmt;
					IPAAbstractOperator op = implicitStmt.getOperator();
					IPAPointsToSetVariable w = (IPAPointsToSetVariable) implicitStmt.getLHS();//lhs
					//in graph: rhs -> lhs
					Integer w_id = w.getGraphNodeId();
					if(w_id == v_id){
						addVertex(w_id);
						continue;
					}
					addEdge(v_id, w_id, false);
				}
			}
		}
		max_id = flowGraph.getNodeManager().getMaxNumber() + 1;
		initializeParams();
	}

	/**
	 * for the whole program
	 */
	public void initializeParams(){
	    visited = new boolean[max_id];
	    stack = new Stack<>();
	    time = 0;
	    lowlink = new int[max_id];
	    sccs = new ArrayList<>();
	    new_sccs = new ArrayList<>();
	    removed_sccs = new ArrayList<>();
	    inner_sccs = new ArrayList<>();
	}

	/**
	 * reset params for incremental
	 */
	private void resetParams() {
		visited = new boolean[max_id];
	    stack = new Stack<>();
	    time = 0;
	    lowlink = new int[max_id];
	    new_sccs.clear();
	    removed_sccs.clear();
	    inner_sccs.clear();
	}

	/**
	/**
	 * append a new vertex containing an empty list to the end of our ArrayList.
	 * @param v : added node id
	 */
	public void addVertex(Integer v) {
	    ArrayList<Integer> neighbors = new ArrayList<Integer>();
	    adjListsMap.put(v, neighbors);
	}

	/**
	 * To deleting a vertex, we remove the last ArrayList in our Map.
	 * @param v : removed node id
	 */
	public void removeVertex(Integer v) {
	    // Remove the vertex at the end
	    adjListsMap.remove(((Integer) v));
	}

	/**
	 * To add an edge, simply retrieve the ArrayList corresponding to the beginning vertex in our Map,
	 * then append the value of the end vertex.
	 * v -> w : rhs -> lhs
	 * @param v
	 * @param w
	 * @param isFilter
	 */
	public void addEdge(Integer v, Integer w, boolean isFilter) {
		ArrayList<Integer> neighbours = adjListsMap.get(v);
		if(neighbours == null){
			addVertex(v);
			neighbours = adjListsMap.get(v);
		}
		neighbours.add(w);
		if(!adjListsMap.containsKey(w)){
			addVertex(w);
		}
//		if(isFilter)
//			addFilterEdge(v, w);
		if(change){
			//check if introduce new sccs
			incrementalSCCForAddEdge(w, v);
		}
	}


	public void addEdgeOnly(Integer v, Integer w, boolean isFilter) {
		ArrayList<Integer> neighbours = adjListsMap.get(v);
		if(neighbours == null){
			addVertex(v);
			neighbours = adjListsMap.get(v);
		}
		neighbours.add(w);
		if(!adjListsMap.containsKey(w)){
			addVertex(w);
		}
	}


	/**
	 * To remove an edge that starts from v and goes to w, remove it from the vertex's list.
	 * v -> w : rhs -> lhs
	 * @param v
	 * @param w
	 * @param isFilter
	 * @throws VertexOutOfBoundsException
	 */
	public void removeEdge(Integer v, Integer w, boolean isFilter){
		// Remove edge that starts from v to w
		ArrayList<Integer> neighbours = adjListsMap.get(v);
		if(neighbours == null){
			throw new ArrayIndexOutOfBoundsException(v);
		}
		neighbours.remove(w);
		if(change){
			//check if remove existing sccs
			incrementalSCCForRemoveEdge(v, w);
		}
	}

	public void removeEdgeOnly(Integer v, Integer w, boolean isFilter){
		// Remove edge that starts from v to w
		ArrayList<Integer> neighbours = adjListsMap.get(v);
		if(neighbours == null){
			throw new ArrayIndexOutOfBoundsException(v);
		}
		neighbours.remove(w);
	}
 
	protected void incrementalSCCForRemoveEdge(Integer v, Integer w) {
		//reset
	    removed_sccs.clear();
	    //find existings should be removed
		for (HashSet<Integer> exist : sccs) {
			if(exist.contains(v) && exist.contains(w)){
				removed_sccs.add(exist);
				break;//should only has one scc
			}
		}
		if(removed_sccs.size() == 0)
			return;
		//find inner sccs inside removed existings
		inner_sccs.clear();
		max_id = flowGraph.getNodeManager().getMaxNumber() + 1;
		for (HashSet<Integer> remove : removed_sccs) {
			visited = new boolean[max_id];
		    stack = new Stack<>();
		    time = 0;
		    lowlink = new int[max_id];
			for (Integer v_id : remove) {
				boolean done = false;
				for (HashSet<Integer> new_scc : inner_sccs) {
					if(new_scc.contains(v_id))
						done = true;
				}
				if(v_id != v && v_id != w && !done){
					if(!visited[v_id]){
						incre_inner_dfs(v_id, remove);
					}
				}
			}
		}
		sccs.removeAll(removed_sccs);
		sccs.addAll(inner_sccs);
//		for (HashSet<Integer> exist : removed_sccs) {
//			print("Remove SCC: ", exist);
//		}
//		for (HashSet<Integer> inner : inner_sccs) {
//			print("Inner SCC: ", inner);
//		}
	}


	public void incrementalSCCForRemoveEdges(ArrayList<Integer> vs, ArrayList<Integer> ws) {
		//reset
		removed_sccs.clear();
	    //find existings should be removed
	    int size = vs.size();
	    int i = 0;
	    HashSet<Integer> checked = new HashSet<>();
	    while(i < size){
	    	Integer v = vs.get(i);
	    	Integer w = ws.get(i);
	    	if(!checked.contains(v) && !checked.contains(w)){
	    		for (HashSet<Integer> exist : sccs) {
	    			if(exist.contains(v) && exist.contains(w)){
	    				removed_sccs.add(exist);
	    				checked.addAll(exist);
	    				break;//should only has one scc
	    			}
	    		}
	    	}
	    	i++;
	    }
	    if(removed_sccs.size() == 0){
	    	return;
	    }
		//find inner sccs inside removed existings
		inner_sccs.clear();
		max_id = flowGraph.getNodeManager().getMaxNumber() + 1;
		for (HashSet<Integer> remove : removed_sccs) {
			visited = new boolean[max_id];
		    stack = new Stack<>();
		    time = 0;
		    lowlink = new int[max_id];
			for (Integer v_id : remove) {
				boolean done = false;
				for (HashSet<Integer> new_scc : inner_sccs) {
					if(new_scc.contains(v_id))
						done = true;
				}
				if(!vs.contains(v_id) && !ws.contains(v_id) && !done){
					if(!visited[v_id]){
						incre_inner_dfs(v_id, remove);
					}
				}
			}
		}
		sccs.removeAll(removed_sccs);
		sccs.addAll(inner_sccs);
//		for (HashSet<Integer> exist : removed_sccs) {
//			print("Remove SCC: ", exist);
//		}
//		for (HashSet<Integer> inner : inner_sccs) {
//			print("Inner SCC: ", inner);
//		}
	}



	/**
	 * perform a whole program scc detection
	 */
	public ArrayList<HashSet<Integer>> initialSCC() {
		for (Integer v_id : adjListsMap.keySet()) {
			if(!visited[v_id]){
				dfs(v_id);
			}
		}
		return sccs;
	}


	/**
	 * perform incremental detection for v_id
	 * @param v_id
	 * @param w_id
	 */
	protected void incrementalSCCForAddEdge(Integer v_id, Integer w_id){
		max_id = flowGraph.getNodeManager().getMaxNumber() + 1;
		resetParams();
		if(!visited[v_id]){
			incre_dfs(v_id, w_id);
		}
		sccs.removeAll(removed_sccs);
	}

	public void incrementalSCCForAddEdges(ArrayList<Integer> rhss, ArrayList<Integer> lhss) {
		max_id = flowGraph.getNodeManager().getMaxNumber() + 1;
		resetParams();
		HashSet<Integer> set = new HashSet<>();
		set.addAll(rhss);
		set.addAll(lhss);
		for (Integer v : set) {
			if(!visited[v]){
				incre_dfs_set(v, set);
			}
		}
		sccs.removeAll(removed_sccs);
	}


	/**
	 * if there exists a scc
	 * @param v_id
	 */
	private void dfs(Integer v_id) {
		lowlink[v_id] = time++;
		visited[v_id] = true;
		stack.add(v_id);
		boolean isComponentRoot = true;

		for (Integer w_id : adjListsMap.get(v_id)) {
			if (!visited[w_id]){
				dfs(w_id);
			}
			if (lowlink[v_id] > lowlink[w_id]) {
				lowlink[v_id] = lowlink[w_id];
				isComponentRoot = false;
			}
		}

		if (isComponentRoot && (stack.size() != 1)) {
			HashSet<Integer> scc = new HashSet<>();
			while (true) {
				Integer x = stack.pop();
				scc.add(x);
				lowlink[x] = Integer.MAX_VALUE;
				if (x == v_id)
					break;
			}
			if(scc.size() != 1){
				sccs.add(scc);
			}
		}
	}



	/**
	 * incremental detect a scc starting from v_id, end with e_id
	 * @param v_id
	 * @param e_id
	 */
	private void incre_dfs(Integer v_id, Integer e_id) {
		lowlink[v_id] = time++;
		visited[v_id] = true;
		stack.add(v_id);
		boolean isComponentRoot = true;

		for (Integer w_id : adjListsMap.get(v_id)) {
			if (!visited[w_id]){
				incre_dfs(w_id, e_id);
			}
			if (lowlink[v_id] > lowlink[w_id]) {
				lowlink[v_id] = lowlink[w_id];
				isComponentRoot = false;
			}
		}

		if (isComponentRoot && (stack.size() != 1)) {
			HashSet<Integer> scc = new HashSet<>();
			while (true) {
				Integer x = stack.pop();
				scc.add(x);
				lowlink[x] = Integer.MAX_VALUE;
				if (x == v_id)
					break;
			}
			if(scc.size() != 1){
				if(!contains(scc) && scc.contains(e_id)){
					//!!!discover a new scc
					new_sccs.add(scc);
					//check:larger scc should include small scc
					for(HashSet<Integer> exist : sccs){
						if(scc.containsAll(exist)){
							removed_sccs.add(exist);//small scc
//							print("Remove Inner SCC:", exist);
						}
					}
					sccs.add(scc);
//					print("Add SCC: ", scc);
				}
			}
		}
	}

	private void incre_dfs_set(Integer v_id, HashSet<Integer> set) {
		lowlink[v_id] = time++;
		visited[v_id] = true;
		stack.add(v_id);
		boolean isComponentRoot = true;

		for (Integer w_id : adjListsMap.get(v_id)) {
			if (!visited[w_id]){
				incre_dfs_set(w_id, set);
			}
			if (lowlink[v_id] > lowlink[w_id]) {
				lowlink[v_id] = lowlink[w_id];
				isComponentRoot = false;
			}
		}

		if (isComponentRoot && (stack.size() != 1)) {
			HashSet<Integer> scc = new HashSet<>();
			while (true) {
				Integer x = stack.pop();
				scc.add(x);
				lowlink[x] = Integer.MAX_VALUE;
				if (x == v_id)
					break;
			}
			if(scc.size() != 1){
				if(!contains(scc)){// && containsAny(scc, set)
					//!!!discover a new scc
					new_sccs.add(scc);
					//check:larger scc should include small scc
					for(HashSet<Integer> exist : sccs){
						if(scc.containsAll(exist)){
							removed_sccs.add(exist);//small scc
//							print("Remove Inner SCC:", exist);
						}
					}
					sccs.add(scc);
//					print("Add SCC: ", scc);
				}
			}
		}
	}


	/**
	 * incremental detect a scc starting from v_id, and all v belong to exist
	 * after edge deletion, discover the inner sccs
	 * @param v_id
	 * @param exist
	 */
	private void incre_inner_dfs(Integer v_id, HashSet<Integer> exist) {
		lowlink[v_id] = time++;
		visited[v_id] = true;
		stack.add(v_id);
		boolean isComponentRoot = true;

		for (Integer w_id : adjListsMap.get(v_id)) {
			if (!visited[w_id]){
				incre_inner_dfs(w_id, exist);
			}
			if (lowlink[v_id] > lowlink[w_id]) {
				lowlink[v_id] = lowlink[w_id];
				isComponentRoot = false;
			}
		}

		if (isComponentRoot && (stack.size() != 1)) {
			HashSet<Integer> scc = new HashSet<>();
			while (true) {
				Integer x = stack.pop();
				scc.add(x);
				lowlink[x] = Integer.MAX_VALUE;
				if (x == v_id)
					break;
			}
			if(scc.size() != 1){
				if((scc.size() < exist.size()) && exist.containsAll(scc)){
					//!!!discover a new scc
					if(!inner_sccs.contains(scc))
						inner_sccs.add(scc);
				}
			}
		}
	}

	public boolean contains(HashSet<Integer> scc){
		for (HashSet<Integer> exist : sccs) {
			if(exist.containsAll(scc) && scc.containsAll(exist)){
				return true;
			}
		}
		return false;
	}


	/**
	 * print all the current sccs
	 */
	public void printAll(){
		System.err.println("**** Current SCCs: (" + sccs.size() + ")");
		for (HashSet<Integer> scc : sccs) {
			System.out.println("-- ids: " + scc.toString());
			if(showdetails){
				System.out.println("-- points-to set variables: ");
				for (Integer v_id : scc) {
					INodeWithNumber node = flowGraph.getNodeManager().getNode(v_id);
					if(node instanceof IPAPointsToSetVariable){
						System.out.println("---- " + node.toString());
					}
				}
			}
		}
	}

	/**
 	 * print the one scc
	 * @param scc
	 */
	public void print(String type, HashSet<Integer> scc){
		System.out.println(type + "-- ids: " + scc.toString());
		if(showdetails){
			System.out.println(type + "-- corresponding points-to set variables: ");
			for (Integer v_id : scc) {
				INodeWithNumber node = flowGraph.getNodeManager().getNode(v_id);
				if(node instanceof IPAPointsToSetVariable){
					System.out.println("---- " + node.toString());
				}else{
					throw new RuntimeException("Wrong INodeWithNumber Ivolved in SCC: " + node.toString());
				}
			}
		}
	}


	// test
//	public static void main(String[] args) {
//		AdjacencyLists graph = new AdjacencyLists();
//		graph.showdetails = false;
//		graph.addVertex(0);
//		graph.addVertex(1);
//		graph.addVertex(2);
//		graph.addEdge(2, 0);
//		graph.addEdge(2, 1);
//		graph.addEdge(0, 1);
//		graph.addEdge(1, 0);
//		graph.max_id = 3;
//		graph.initializeParams();
//		graph.initialSCC();
//		graph.printAll();
//		//incre test
//		graph.setChange(true);
//		graph.addEdge(0, 2);
//		graph.printAll();
//		graph.removeEdge(1, 0);
//		graph.printAll();
//		graph.addEdge(0, 3);
//		graph.printAll();
//		graph.addEdge(3, 2);
//		graph.printAll();
//		graph.removeEdge(2, 0);
//		graph.printAll();
//	}


}
