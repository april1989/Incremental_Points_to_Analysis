package edu.tamu.wala.increpta.util;

import java.util.Random;

public class MapCoordinateGenerator {

	static double low = -10000;
	static double high = 10000;

	public static double generateRandomCoordinate(){
		Random random = new Random();
		double coordinate = low + (high - low) * random.nextDouble();
		return coordinate;
	}

}
