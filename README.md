## WALA Subproject: Incremental Points-to Analysis [![Build Status](https://travis-ci.org/april1989/Incremental_Points_to_Analysis.svg?branch=master)](https://travis-ci.org/april1989/Incremental_Points_to_Analysis)

This is the main source repository for the incremental points-to analysis, developed by Automated Software Engineering Research (ASER) Group at Texas A&M University. The source code is in ```edu.tamu.wala.increpta```, and the tests are in ```edu.tamu.wala.increpta.tests```. 

### Run the Junit Tests
The JUnit test file is in ```edu.tamu.wala.increpta.tests```. The test cases includeds all the test cases in ```com.ibm.wala.core.testdata/src/demandpa``` for pointer analysis. 

You can checkout the projects in the master branch, and import them into Eclipse. Locate the JUnit test (```edu.tamu.wala.increpta.tests```) and run as JUnit test. There are two potential problems during the test:
- unsupported major.minor version 52.0, please go to _Run Configuration_ -> JRE -> Alternative -> Java 8, and then run it. 
- if you would like to build the whole wala project in this repo, please download the DroidBench and add the path in ```wala.properties```.

You can also run maven script to build the project by ```mvn clean install```.

### Test Methodology
For each test case, we initially compute the whole program pointer analysis, record the points-to set for each variable in a map. Then, we start to check the correctness of the incremental pointer analysis:
(1) delete an SSAInstruction, compute the affected points-to sets;
(2) add back the SSAInstruction, compute the affected points-to sets and record the changed variables
(3) for each changed variable, we compare the points-to sets between the one stored in the map and the one after (2). If they are the same, it means we correctly updated the points-to graph. Otherwise, it violates the assertion.

### Parallelization
The incremental points-to analysis can be embarassingly paralleled to improve the performance. For the parallel version, please change ```edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationSystem.nrOfWorkers```, which indicates the number of threads to perform parallel works.

### About WALA
The main repositry for WALA is https://github.com/wala/WALA. For more details on WALA, see <a
href="http://wala.sourceforge.net">the WALA home page</a> and <a href="https://wala.github.io/javadoc">WALA Javadoc</a>.
