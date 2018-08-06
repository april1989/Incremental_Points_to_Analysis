package edu.tamu.wala.increpta.scc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Stack;

import com.ibm.wala.ipa.callgraph.propagation.PointsToSetVariable;
import com.ibm.wala.util.graph.INodeWithNumber;

import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationCallGraphBuilder.IPAFilterOperator;
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

	//op = filteroperator should process separately, currently use IPAFilteredPointerKey in sccengine to simple it.
	private HashMap<Integer, ArrayList<Integer>> filter_AdjListsMap;

	public AdjacencyLists() {
		adjListsMap = new HashMap<>();
		filter_AdjListsMap = new HashMap<>();
	}

	public AdjacencyLists(IPAPropagationGraph flowGraph) {
		this.flowGraph = flowGraph;
		adjListsMap = new HashMap<>();
		filter_AdjListsMap = new HashMap<>();
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
		Iterator<PointsToSetVariable> iterator = flowGraph.getVariables();
		while (iterator.hasNext()) {
			PointsToSetVariable v = (PointsToSetVariable) iterator.next();//should be rhs
			int v_id = v.getGraphNodeId();
			ArrayList<IPAAbstractStatement> stmts = flowGraph.getImplicitStatementsThatUse(v);
			for (IPAAbstractStatement stmt : stmts) {
				if(stmt instanceof IPAUnaryStatement){
					IPAUnaryStatement implicitStmt = (IPAUnaryStatement) stmt;
					IPAAbstractOperator op = implicitStmt.getOperator();
					PointsToSetVariable w = (PointsToSetVariable) implicitStmt.getLHS();//lhs
					//in graph: rhs -> lhs
					int w_id = w.getGraphNodeId();
					addEdge(v_id, w_id, false);
					if(op instanceof IPAFilterOperator){
						addFilterEdge(v_id, w_id);
					}
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
	public void addVertex(int v) {
	    ArrayList<Integer> neighbors = new ArrayList<Integer>();
	    adjListsMap.put(v, neighbors);
	}

	public void addFilterVertex(int v) {
	    ArrayList<Integer> neighbors = new ArrayList<Integer>();
	    filter_AdjListsMap.put(v, neighbors);
	}

	/**
	 * To deleting a vertex, we remove the last ArrayList in our Map.
	 * @param v : removed node id
	 */
	public void removeVertex(int v) {
	    // Remove the vertex at the end
	    adjListsMap.remove(((Integer) v));
	    removeFilterVertex(v);
	}

	public void removeFilterVertex(int v) {
	    // Remove the vertex at the end
	    filter_AdjListsMap.remove(((Integer) v));
	}

	/**
	 * To add an edge, simply retrieve the ArrayList corresponding to the beginning vertex in our Map,
	 * then append the value of the end vertex.
	 * v -> w : rhs -> lhs
	 * @param v
	 * @param w
	 * @param isFilter
	 */
	public void addEdge(int v, int w, boolean isFilter) {
		ArrayList<Integer> neighbours = adjListsMap.get(v);
		if(neighbours == null){
			addVertex(v);
			neighbours = adjListsMap.get(v);
		}
		neighbours.add(w);
		if(!adjListsMap.containsKey(w)){
			addVertex(w);
		}
		if(isFilter)
			addFilterEdge(v, w);
		if(change){
			//check if introduce new sccs
			incrementalSCCForAddEdge(w, v);
		}
	}


	private void addFilterEdge(int v, int w) {
		ArrayList<Integer> neighbours = filter_AdjListsMap.get(v);
		if(neighbours == null){
			addFilterVertex(v);
			neighbours = filter_AdjListsMap.get(v);
		}
		neighbours.add(w);
		if(!filter_AdjListsMap.containsKey(w)){
			addFilterVertex(w);
		}
	}


	public boolean hasFilterEdges(HashSet<Integer> scc){
		for (Integer v : scc) {
			ArrayList<Integer> neighbours = filter_AdjListsMap.get(v);
			if(neighbours == null)
				continue;
			for (Integer w : neighbours) {
				if(v != w && scc.contains(w)){
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * To remove an edge that starts from v and goes to w, remove it from the vertex's list.
	 * v -> w : rhs -> lhs
	 * @param v
	 * @param w
	 * @param isFilter
	 * @throws VertexOutOfBoundsException
	 */
	public void removeEdge(int v, int w, boolean isFilter){
		// Remove edge that starts from v to w
		ArrayList<Integer> neighbours = adjListsMap.get(v);
		if(neighbours == null){
			throw new ArrayIndexOutOfBoundsException(v);
		}
		neighbours.remove(((Integer) w));
		if(isFilter)
			removeFilterEdge(v, w);
		if(change){
			//check if remove existing sccs
			incrementalSCCForRemoveEdge(v, w);
		}
	}

	public void removeFilterEdge(int v, int w){
		// Remove edge that starts from v to w
		ArrayList<Integer> neighbours = filter_AdjListsMap.get(v);
		if(neighbours == null){
			throw new ArrayIndexOutOfBoundsException(v);
		}
		neighbours.remove(((Integer) w));
	}

	private void incrementalSCCForRemoveEdge(int v, int w) {
		//reset
	    removed_sccs.clear();
	    //find existings should be removed
		for (HashSet<Integer> exist : sccs) {
			if(exist.contains(v) && exist.contains(w)){
				removed_sccs.add(exist);
				print("Remove: ", exist);
				break;//should only has one scc
			}
		}
		sccs.removeAll(removed_sccs);
		//find inner sccs inside removed existings
		inner_sccs.clear();
		max_id = flowGraph.getNodeManager().getMaxNumber() + 1;
		for (HashSet<Integer> remove : removed_sccs) {
			for (Integer v_id : remove) {
				boolean done = false;
				for (HashSet<Integer> new_scc : inner_sccs) {
					if(new_scc.contains(v_id))
						done = true;
				}
				if(v_id != v && v_id != w && !done){
					visited = new boolean[max_id];
				    stack = new Stack<>();
				    time = 0;
				    lowlink = new int[max_id];
					if(!visited[v_id]){
						incre_inner_dfs(v_id, remove);
					}
				}
			}
		}
	}

	/**
	 * perform a whole program scc detection
	 */
	public ArrayList<HashSet<Integer>> initialSCC() {
		for (int v_id : adjListsMap.keySet()) {
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
	public void incrementalSCCForAddEdge(int v_id, int w_id){
		max_id = flowGraph.getNodeManager().getMaxNumber() + 1;
		resetParams();
		if(!visited[v_id]){
			incre_dfs(v_id, w_id);
		}
		sccs.removeAll(removed_sccs);
	}

	/**
	 * if there exists a scc
	 * @param v_id
	 */
	private void dfs(int v_id) {
		lowlink[v_id] = time++;
		visited[v_id] = true;
		stack.add(v_id);
		boolean isComponentRoot = true;

		for (int w_id : adjListsMap.get(v_id)) {
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
				int x = stack.pop();
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
	private void incre_dfs(int v_id, int e_id) {
		lowlink[v_id] = time++;
		visited[v_id] = true;
		stack.add(v_id);
		boolean isComponentRoot = true;

		for (int w_id : adjListsMap.get(v_id)) {
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
				int x = stack.pop();
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
						}
					}
					sccs.add(scc);
					print("Add: ", scc);
				}else if(!contains(scc)){
					//but scc ! contain e_id
					throw new RuntimeException("Discover a scc that should not be discovered: some pointer should be discarded.");
				}//else{ sccs contain scc: no new sccs }
			}
		}
	}

	/**
	 * incremental detect a scc starting from v_id, and all v belong to exist
	 * after edge deletion, discover the inner sccs
	 * @param v_id
	 * @param exist
	 */
	private void incre_inner_dfs(int v_id, HashSet<Integer> exist) {
		lowlink[v_id] = time++;
		visited[v_id] = true;
		stack.add(v_id);
		boolean isComponentRoot = true;

		for (int w_id : adjListsMap.get(v_id)) {
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
				int x = stack.pop();
				scc.add(x);
				lowlink[x] = Integer.MAX_VALUE;
				if (x == v_id)
					break;
			}
			if(scc.size() != 1){
				if(!contains(scc) && exist.containsAll(scc)){
					//!!!discover a new scc
					inner_sccs.add(scc);
					sccs.add(scc);
					print("Inner: ", scc);
				}else if(!contains(scc)){
					//but scc ! contain e_id
					throw new RuntimeException("Discover a scc that should not be discovered: some pointer should be discarded.");
				}//else{ sccs contain scc: no new sccs }
			}
		}
	}

	public boolean contains(HashSet<Integer> scc){
		for (HashSet<Integer> exist : sccs) {
			if(exist.equals(scc)){
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
					if(node instanceof PointsToSetVariable){
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
				if(node instanceof PointsToSetVariable){
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
