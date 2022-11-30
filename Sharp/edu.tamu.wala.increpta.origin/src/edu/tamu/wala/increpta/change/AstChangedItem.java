package edu.tamu.wala.increpta.change;


/**
 * all changes can be confirmed from ast parser 
 * @author Bozhen
 */
public class AstChangedItem {
	public String packageName="";
	public String className="";
	public String methodName="";
	
	/**
	 * only refer to origin annotation changes, 
	 * -1: no change; 0: delete; 1: add; 2:change name. 
	 */
	public int annotationChange = -1; 
	
}