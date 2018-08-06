package edu.tamu.wala.increpta.scc;

import java.util.ArrayList;
import java.util.HashSet;

import com.ibm.wala.ipa.callgraph.propagation.PointsToSetVariable;
import com.ibm.wala.util.graph.INodeWithNumber;
import com.ibm.wala.util.graph.NumberedNodeManager;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.IntSetAction;
import com.ibm.wala.util.intset.MutableIntSet;
import com.ibm.wala.util.intset.MutableSharedBitVectorIntSet;

import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationGraph;
import edu.tamu.wala.increpta.operators.IPAAbstractOperator;
import edu.tamu.wala.increpta.operators.IPAAbstractStatement;
import edu.tamu.wala.increpta.operators.IPAAssignOperator;
import edu.tamu.wala.increpta.operators.IPAUnaryStatement;

public class SCCVariable extends PointsToSetVariable{

	private HashSet<Integer> variable_ids;
	private HashSet<PointsToSetVariable> variables;
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
	 * include all PointsToSetVariable, set its value as the union of all PointsToSetVariable
	 * @param flowGraph
	 */
	public void fillInVariables(IPAPropagationGraph flowGraph){
		this.flowGraph = flowGraph;
		NumberedNodeManager<INodeWithNumber> manager = flowGraph.getNodeManager();
		for (Integer id : variable_ids) {
			INodeWithNumber node = manager.getNode(id);
			if(node instanceof PointsToSetVariable){
				variables.add((PointsToSetVariable) node);
				MutableIntSet intset = ((PointsToSetVariable) node).getValue();
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

	public boolean belongToThisSCC(PointsToSetVariable v){
	  return variables.contains(v);
	}

	public HashSet<PointsToSetVariable> getInvolvedVariables() {
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
	public boolean ifOthersCanProvide(PointsToSetVariable exclude, MutableSharedBitVectorIntSet remaining,
	    MutableIntSet set, IPAPropagationGraph flowGraph){
		for (PointsToSetVariable v : variables) {
			if(!v.equals(exclude)){
				for (IPAAbstractStatement def : flowGraph.getImplicitStatementsThatDef(v)) {
					IPAUnaryStatement udef = (IPAUnaryStatement) def;
					PointsToSetVariable rhs = (PointsToSetVariable) udef.getRightHandSide();
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
	public void updateForAdd(HashSet<Integer> adds, IPAPropagationGraph flowGraph, int rhs) {
		variable_ids.addAll(adds);
		NumberedNodeManager<INodeWithNumber> manager = flowGraph.getNodeManager();
		for (Integer add : adds) {
			INodeWithNumber v = manager.getNode(add);
			variables.add((PointsToSetVariable) v);
		}
    //////inner PointsToSetVariable update is performed by outside worklist algorithm
//    PointsToSetVariable rhs_v = (PointsToSetVariable) manager.getNode(rhs);//start point
//    //update this value
//    if(rhs_v.getValue() != null)
//      addAll(rhs_v.getValue());
//    //other v
//    ArrayList<IPAAbstractStatement> uses = flowGraph.getImplicitStatementsThatUse(rhs_v);
//    ArrayList<IPAAbstractStatement> temp = new ArrayList<>();
//    int k = adds.size();
//    while(k > 0){//only update the int in adds
//    	for (IPAAbstractStatement use : uses) {
//    		PointsToSetVariable lhs_v = (PointsToSetVariable) use.getLHS();
//    		int lhs = lhs_v.getGraphNodeId();
//    		if(adds.contains(lhs)){
//    			use.evaluate();
//    			rhs_v = (PointsToSetVariable) ((IPAUnaryStatement) use).getRightHandSide();
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
	public void updateForDelete(HashSet<Integer> deletes, IPAPropagationGraph flowGraph, int rhs) {
		variable_ids.removeAll(deletes);
		NumberedNodeManager<INodeWithNumber> manager = flowGraph.getNodeManager();
		for (Integer delete : deletes) {
			INodeWithNumber v = manager.getNode(delete);
			variables.remove((PointsToSetVariable) v);
		}
	    //////inner PointsToSetVariable update is performed by outside worklist algorithm
//    PointsToSetVariable rhs_v = (PointsToSetVariable) manager.getNode(rhs);//start point
//    //update this value
//    if(rhs_v.getValue() != null)
//      removeAll(rhs_v.getValue());
//    //other v
//    ArrayList<IPAAbstractStatement> uses = flowGraph.getImplicitStatementsThatUse(rhs_v);
//    ArrayList<IPAAbstractStatement> temp = new ArrayList<>();
//    int k = deletes.size();
//    while(k > 0){//only update the int in adds
//      for (IPAAbstractStatement use : uses) {
//        PointsToSetVariable lhs_v = (PointsToSetVariable) use.getLHS();
//        int lhs = lhs_v.getGraphNodeId();
//        if(deletes.contains(lhs)){
//          IPAAbstractOperator op = use.getOperator();
//          if(op instanceof IPAAssignOperator){
//        	  evaluateAssignDel(lhs_v, rhs_v);
//          }else{
//            use.evaluateDel();
//          }
//          rhs_v = (PointsToSetVariable) ((IPAUnaryStatement) use).getRightHandSide();
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
    //////inner PointsToSetVariable update is performed by outside worklist algorithm
    //we need to solve each pts for each variable here
	 * @param that
	 * @param rhs
	 * @return
	 */
  public boolean addAll(MutableSharedBitVectorIntSet that, int rhs){
    NumberedNodeManager<INodeWithNumber> manager = flowGraph.getNodeManager();
    PointsToSetVariable rhs_v = (PointsToSetVariable) manager.getNode(rhs);//start point
    //update this value
    if(rhs_v.getValue() != null)
      addAll(rhs_v.getValue());
    //other v
    ArrayList<IPAAbstractStatement> uses = flowGraph.getImplicitStatementsThatUse(rhs_v);
    ArrayList<IPAAbstractStatement> temp = new ArrayList<>();
    int k = variables.size();
    while(k > 0){//only update the int in adds
      for (IPAAbstractStatement use : uses) {
        PointsToSetVariable lhs_v = (PointsToSetVariable) use.getLHS();
        if(variables.contains(lhs_v)){
          use.evaluate();
          rhs_v = (PointsToSetVariable) ((IPAUnaryStatement) use).getRightHandSide();
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
    //////inner PointsToSetVariable update is performed by outside worklist algorithm
    //we need to solve each pts for each variable here
   * @param that
   * @param rhs
   * @return
   */
  public boolean removeAll(MutableSharedBitVectorIntSet that, int rhs){
    NumberedNodeManager<INodeWithNumber> manager = flowGraph.getNodeManager();
    PointsToSetVariable rhs_v = (PointsToSetVariable) manager.getNode(rhs);//start point
    //update this value
    if(rhs_v.getValue() != null)
      removeAll(rhs_v.getValue());
    //other v
    ArrayList<IPAAbstractStatement> uses = flowGraph.getImplicitStatementsThatUse(rhs_v);
    ArrayList<IPAAbstractStatement> temp = new ArrayList<>();
    int k = variables.size();
    while(k > 0){//only update the int in adds
      for (IPAAbstractStatement use : uses) {
        PointsToSetVariable lhs_v = (PointsToSetVariable) use.getLHS();
        if(variables.contains(lhs_v)){
          IPAAbstractOperator op = use.getOperator();
          if(op instanceof IPAAssignOperator){
            evaluateAssignDel(lhs_v, rhs_v);
          }else{
            use.evaluateDel();
          }
          rhs_v = (PointsToSetVariable) ((IPAUnaryStatement) use).getRightHandSide();
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
  public boolean evaluateAssignDel(PointsToSetVariable lhs, PointsToSetVariable rhs){
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
//////inner PointsToSetVariable update is performed by outside worklist algorithm
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


