package edu.tamu.wala.increpta.tests.git;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.TreeSet;

import com.ibm.wala.util.collections.HashSetFactory;

import akka.event.LoggingAdapter;

/**
 * collect all kinds of exceptions 
 * @author bozhen
 *
 */
public class LogUtil {
	
    ////////// logger //////////  
	private static boolean LOCAL = true;
	private static LoggingAdapter log;
	
	public static void setLogger(boolean local, LoggingAdapter log) {
		LogUtil.LOCAL = local;
		LogUtil.log = log;
	}
	
	/**
	 * bz: for remote and local
	 * @param msg
	 */
	public static synchronized void logMe(String msg) {
		if(LOCAL)
			System.out.println(msg);
		else
			log.info(msg);
	}
	
	
	public static boolean hasThingToSay() {
		return responses.size() > 0 || exceptions.size() > 0;
	}
	
    ////////// message to gitapp user //////////  
	private final static ArrayList<String> responses = new ArrayList<>();
	
	public static synchronized boolean addResponse(String response) {
		return responses.add(response);
	}
	
	public static synchronized String dumpResponses() {
		StringBuffer buffer = new StringBuffer();
		if(responses.size() > 0)
			buffer.append("Problems: \n");
		
		for(int i=0; i<responses.size(); i++) {
			String re = responses.get(i);
			buffer.append(i + 1).append(". ");
			buffer.append(re);
			buffer.append('\n');
		}
		
		clearResponse();
		return buffer.toString();
	}
	
	public static synchronized void clearResponse() {
		responses.clear();
	}
	
    ////////// exception recorder //////////  
	private final static Collection<Exception> exceptions = HashSetFactory.make();

	public static synchronized boolean add(Exception e) {
		return exceptions.add(e);
	}

	public static synchronized void clear() {
		exceptions.clear();
	}
	
	public static synchronized String dumpExceptions() {
		StringBuilder buffer = new StringBuilder();
		if(exceptions.size() > 0)
			buffer.append("Exceptions: \n");
		
		Object[] array = exceptions.toArray();
		for(int i = 0; i < array.length; i++) {
			Exception exception = (Exception) array[i];
			buffer.append("Exception " + (i + 1) + " ------------------------------------------------------------------------------\n");
			buffer.append(exception.getMessage() + "\n");
			
			buffer.append("Stacks: \n");
			StackTraceElement[] stacks = exception.getStackTrace();
			for(int j=0; j<stacks.length; j++) {
				StackTraceElement stack = stacks[j];
				buffer.append(j + 1).append(" ");
				buffer.append(stack.toString());
				buffer.append('\n');
			}
			buffer.append("------------------------------------------------------------------------------\n");
			buffer.append('\n');
		}
		
		clear();
		return buffer.toString();
	}

	public static synchronized Iterator<Exception> iterator() {
		return exceptions.iterator();
	}

}
