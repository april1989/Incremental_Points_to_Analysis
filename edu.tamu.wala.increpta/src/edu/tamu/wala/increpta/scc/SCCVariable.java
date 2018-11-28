package edu.tamu.wala.increpta.scc;

import java.util.ArrayList;
import java.util.HashSet;

import com.ibm.wala.util.graph.INodeWithNumber;
import com.ibm.wala.util.graph.NumberedNodeManager;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.IntSetAction;
import com.ibm.wala.util.intset.MutableIntSet;
import com.ibm.wala.util.intset.MutableSharedBitVectorIntSet;

import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPointsToSetVariable;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationGraph;
import edu.tamu.wala.increpta.operators.IPAAbstractOperator;
import edu.tamu.wala.increpta.operators.IPAAbstractStatement;
import edu.tamu.wala.increpta.operators.IPAAssignOperator;
import edu.tamu.wala.increpta.operators.IPAUnaryStatement;


public class SCCVariable extends IPAPointsToSetVariable{

	private HashSet<Integer> variable_ids;
	private HashSet<IPAPointsToSetVariable> variables;
	private IPAPropagationGraph flowGraph;//save for later solve add/delete()

	/**
	 * store pointstosetvariable_ids, variables,
	 * always call  fillInVariables() immediate after
	 * @param pKey
	 * @param scc
	 */
	public SCCVariable(SSCPointerKey pKey, HashSet<Integer> scc) {
		super(pKey);
		if (scc == null) {
			throw new IllegalArgumentException("null scc");
		}
		this.variable_ids = scc;
		this.variables = new HashSet<>();
		//followed by fillInVariables()
	}

	/**
	 * include all IPAPointsToSetVariable, set its value as the union of all IPAPointsToSetVariable
	 * @param flowGraph
	 */
	public void fillInVariables(IPAPropagationGraph flowGraph){
		this.flowGraph = flowGraph;
		NumberedNodeManager<INodeWithNumber> manager = flowGraph.getNodeManager();
		for (Integer id : variable_ids) {
			INodeWithNumber node = manager.getNode(id);
			if(node instanceof IPAPointsToSetVariable){
				variables.add((IPAPointsToSetVariable) node);
				MutableIntSet intset = ((IPAPointsToSetVariable) node).getValue();
				if(intset != null && intset.size() > 0){
					addAll(intset);
				}
			}
		}
	}


	public HashSet<Integer> getPointsToSetVariableIDs() {
		return variable_ids;
	}

	public boolean belongToThisSCC(int id){
		return variable_ids.contains(id);
	}

	public boolean belongToThisSCC(IPAPointsToSetVariable v){
	  return variables.contains(v);
	}

	public HashSet<IPAPointsToSetVariable> getInvolvedVariables() {
		return variables;
	}

	/**
	 * see if other v in the scc has the incoming neighbour that can provide instances in set
	 * @param exclude
	 * @param set
	 * @param set
	 * @param flowGraph
	 * @return
	 */
	public boolean ifOthersCanProvide(IPAPointsToSetVariable exclude, MutableSharedBitVectorIntSet remaining,
	    MutableIntSet set, IPAPropagationGraph flowGraph){
		for (IPAPointsToSetVariable v : variables) {
			if(!v.equals(exclude)){
				for (IPAAbstractStatement def : flowGraph.getImplicitStatementsThatDef(v)) {
					IPAUnaryStatement udef = (IPAUnaryStatement) def;
					IPAPointsToSetVariable rhs = (IPAPointsToSetVariable) udef.getRightHandSide();
					MutableIntSet value = rhs.getValue();
					if(value != null){
						value.foreach(new IntSetAction() {
							@Override
							public void act(int x) {
								if(remaining.isEmpty())
									return;
								if(set.contains(x))
									remaining.remove(x);
							}
						});
					}
					if(remaining.isEmpty()){
					  return true;
					}
				}
			}
		}
		return false;
	}

	//do not use
	@Override
	public MutableIntSet getValue() {
		return super.getValue();
	}


	/**
	 * should not process the rhs -> lhs
	 * @param adds
	 * @param flowGraph
	 * @param rhs
	 * @param filter: true -> newly added filters
	 */
	public void updateForAdd(HashSet<Integer> adds, IPAPropagationGraph flowGraph) {
		variable_ids.addAll(adds);
		NumberedNodeManager<INodeWithNumber> manager = flowGraph.getNodeManager();
		for (Integer add : adds) {
			INodeWithNumber v = manager.getNode(add);
			variables.add((IPAPointsToSetVariable) v);
		}
    //////inner IPAPointsToSetVariable update is performed by outside worklist algorithm
//    IPAPointsToSetVariable rhs_v = (IPAPointsToSetVariable) manager.getNode(rhs);//start point
//    //update this value
//    if(rhs_v.getValue() != null)
//      addAll(rhs_v.getValue());
//    //other v
//    ArrayList<IPAAbstractStatement> uses = flowGraph.getImplicitStatementsThatUse(rhs_v);
//    ArrayList<IPAAbstractStatement> temp = new ArrayList<>();
//    int k = adds.size();
//    while(k > 0){//only update the int in adds
//    	for (IPAAbstractStatement use : uses) {
//    		IPAPointsToSetVariable lhs_v = (IPAPointsToSetVariable) use.getLHS();
//    		int lhs = lhs_v.getGraphNodeId();
//    		if(adds.contains(lhs)){
//    			use.evaluate();
//    			rhs_v = (IPAPointsToSetVariable) ((IPAUnaryStatement) use).getRightHandSide();
//    			temp = flowGraph.getImplicitStatementsThatUse(rhs_v);
//    			k --;
//    			break;
//    		}
//    	}
//    	uses.clear();
//    	uses.addAll(temp);
//    	temp.clear();
//    }
	}

	/**
	 * @param deletes
	 * @param filter: true -> remains still have filters.
	 */
	public void updateForDelete(HashSet<Integer> deletes, IPAPropagationGraph flowGraph) {
		variable_ids.removeAll(deletes);
		NumberedNodeManager<INodeWithNumber> manager = flowGraph.getNodeManager();
		for (Integer delete : deletes) {
			INodeWithNumber v = manager.getNode(delete);
			variables.remove((IPAPointsToSetVariable) v);
		}
	    //////inner IPAPointsToSetVariable update is performed by outside worklist algorithm
//    IPAPointsToSetVariable rhs_v = (IPAPointsToSetVariable) manager.getNode(rhs);//start point
//    //update this value
//    if(rhs_v.getValue() != null)
//      removeAll(rhs_v.getValue());
//    //other v
//    ArrayList<IPAAbstractStatement> uses = flowGraph.getImplicitStatementsThatUse(rhs_v);
//    ArrayList<IPAAbstractStatement> temp = new ArrayList<>();
//    int k = deletes.size();
//    while(k > 0){//only update the int in adds
//      for (IPAAbstractStatement use : uses) {
//        IPAPointsToSetVariable lhs_v = (IPAPointsToSetVariable) use.getLHS();
//        int lhs = lhs_v.getGraphNodeId();
//        if(deletes.contains(lhs)){
//          IPAAbstractOperator op = use.getOperator();
//          if(op instanceof IPAAssignOperator){
//        	  evaluateAssignDel(lhs_v, rhs_v);
//          }else{
//            use.evaluateDel();
//          }
//          rhs_v = (IPAPointsToSetVariable) ((IPAUnaryStatement) use).getRightHandSide();
//          temp = flowGraph.getImplicitStatementsThatUse(rhs_v);
//          k --;
//          break;
//        }
//      }
//      uses.clear();
//      uses.addAll(temp);
//      temp.clear();
//    }
	}


	/**
    //////inner IPAPointsToSetVariable update is performed by outside worklist algorithm
    //we need to solve each pts for each variable here
	 * @param that
	 * @param rhs
	 * @return
	 */
  public boolean addAll(MutableSharedBitVectorIntSet that, int rhs){
    NumberedNodeManager<INodeWithNumber> manager = flowGraph.getNodeManager();
    IPAPointsToSetVariable rhs_v = (IPAPointsToSetVariable) manager.getNode(rhs);//start point
    //update this value
    if(rhs_v.getValue() != null)
      addAll(rhs_v.getValue());
    //other v
    ArrayList<IPAAbstractStatement> uses = flowGraph.getImplicitStatementsThatUse(rhs_v);
    ArrayList<IPAAbstractStatement> temp = new ArrayList<>();
    int k = variables.size();
    while(k > 0){//only update the int in adds
      for (IPAAbstractStatement use : uses) {
        IPAPointsToSetVariable lhs_v = (IPAPointsToSetVariable) use.getLHS();
        if(variables.contains(lhs_v)){
          use.evaluate();
          rhs_v = (IPAPointsToSetVariable) ((IPAUnaryStatement) use).getRightHandSide();
          temp = flowGraph.getImplicitStatementsThatUse(rhs_v);
          k --;
          break;
        }
      }
      uses.clear();
      uses.addAll(temp);
      temp.clear();
    }
	  return true;
  }

  /**
    //////inner IPAPointsToSetVariable update is performed by outside worklist algorithm
    //we need to solve each pts for each variable here
   * @param that
   * @param rhs
   * @return
   */
  @SuppressWarnings("unused")
  public boolean removeAll(MutableSharedBitVectorIntSet that, int rhs){
    NumberedNodeManager<INodeWithNumber> manager = flowGraph.getNodeManager();
    IPAPointsToSetVariable rhs_v = (IPAPointsToSetVariable) manager.getNode(rhs);//start point
    //update this value
    if(rhs_v.getValue() != null)
      removeAll(rhs_v.getValue());
    //other v
    ArrayList<IPAAbstractStatement> uses = flowGraph.getImplicitStatementsThatUse(rhs_v);
    ArrayList<IPAAbstractStatement> temp = new ArrayList<>();
    int k = variables.size();
    while(k > 0){//only update the int in adds
      for (IPAAbstractStatement use : uses) {
        IPAPointsToSetVariable lhs_v = (IPAPointsToSetVariable) use.getLHS();
        if(variables.contains(lhs_v)){
          IPAAbstractOperator op = use.getOperator();
          if(op instanceof IPAAssignOperator){
            evaluateAssignDel(lhs_v, rhs_v);
          }else{
            use.evaluateDel();
          }
          rhs_v = (IPAPointsToSetVariable) ((IPAUnaryStatement) use).getRightHandSide();
          temp = flowGraph.getImplicitStatementsThatUse(rhs_v);
          k --;
          break;
        }
      }
      uses.clear();
      uses.addAll(temp);
      temp.clear();
    }
    return true;
  }

  /*
   * evaluateDel() for assign operator
   */
  public boolean evaluateAssignDel(IPAPointsToSetVariable lhs, IPAPointsToSetVariable rhs){
    boolean removed = false;
    MutableIntSet value = lhs.getValue();
    rhs.getValue().foreach(new IntSetAction() {
      @Override
      public void act(int id) {
        if(value.contains(id)){
          value.remove(id);
        }
      }
    });
    return removed ? true : false;
  }

  /**
   * do i need to check here? should be checked before, then remove.
   * @param value
   */
  private void removeAll(MutableIntSet value) {
    value.foreach(new IntSetAction() {
		  @Override
		  public void act(int remove) {
			  if(getValue().contains(remove)){
				  remove(remove);
			  }
		  }
	  });
  }

////////do not use, other than initialize the scc
//////inner IPAPointsToSetVariable update is performed by outside worklist algorithm
	@Override
	public boolean addAll(IntSet B) {
		return super.addAll(B);
	}

	@Override
	public void remove(int i) {
	   //we need to solve each pts for each variable here
		super.remove(i);
	}

}


