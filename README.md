## Incremental Points-to Analysis: Adopted by WALA [![Build Status](https://travis-ci.org/april1989/Incremental_Points_to_Analysis.svg?branch=master)](https://travis-ci.org/april1989/Incremental_Points_to_Analysis)

This is the main source repository for the incremental points-to analysis, developed by Automated Software Engineering Research (ASER) Group at Texas A&M University. Currently, we support context/flow-insensitive and field-sensitive points-to analysis. The source code is in ```edu.tamu.wala.increpta```, and the tests are in ```edu.tamu.wala.increpta.tests```. 

### Get Started
#### Prerequisites:
- Java >= 1.8
- Eclipse >= Mars 4.5.2
- Apache Maven >= 3.3.9
#### Set Up
1. ```git clone``` the core projects of WALA (https://github.com/wala/WALA)
2. ```git clone https://github.com/april1989/Incremental_Points_to_Analysis.git```
3. With Maven: 
```cd path-to-the-clone-folder```
```mvn clean install -DskipTests=true```
4. With Eclipse:
Import the projects in this repo to a new Eclipse workspace;
Follow the instructions on the "UserGuide: Getting Started":http://wala.sourceforge.net/wiki/index.php/UserGuide:Getting_Started to set up WALA.

#### Example
All the JUnit tests are examples, which the core part is at ```edu.tamu.wala.increpta.tests/src/edu/tamu/wala/increpta/tests/basic/IPAAbstractPtrTest```.

Firstly, we run the points-to analysis for the whole target program, which has the same code structure as provided by WALA points-to analysis framework.
```MY_EXCLUSIONS``` includes all the packages excluded from the analysis, and ```Scope_File``` indicates the target program.

Secondly, for each IR statement addition/deletion, we perform the incremental analysis as indicated in ```testChange``` of ```edu.tamu.wala.increpta/src/edu/tamu/wala/increpta/ipa/callgraph/propagation/IPAPropagationCallGraphBuilder```.

Finally, users can query the updated points-to sets by ```getPointsToSet()```.

### Run the Junit Tests
The JUnit test file is in ```edu.tamu.wala.increpta.tests.basic```. 

The test cases includeds all the test cases in ```com.ibm.wala.core.testdata/src/demandpa```, as well as large benchmarks, e.g., ```h2```, ```sunflow``` in Dacapo. 

You can checkout the projects in the master branch, and import them into Eclipse. Locate the JUnit test (```edu.tamu.wala.increpta.tests```) and run as JUnit test. 

You can also run maven script to build the project by ```mvn clean install```, or ```mvn clean install -DskipTests``` to skip the tests.

#### Test Methodology
For each test case, we initially compute the whole program points-to analysis, record the points-to set for each variable in a map. Then, we start to check the correctness of the incremental pointer analysis:
1. Delete an SSAInstruction, compute the affected points-to sets;
2. Add back the SSAInstruction, compute the affected points-to sets and record the changed variables
3. For each changed variable, we compare the points-to sets between the one stored in the map and the one after (2). If they are the same, it means we correctly updated the points-to graph. Otherwise, it violates the assertion.

#### Parallelization
The incremental points-to analysis can be embarassingly paralleled to improve the performance. For the parallel version, please change ```edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationSystem.nrOfWorkers```, which indicates the number of threads to perform parallel works.

### About WALA
The main repositry for WALA is https://github.com/wala/WALA. For more details on WALA, see <a
href="http://wala.sourceforge.net">the WALA home page</a> and <a href="https://wala.github.io/javadoc">WALA Javadoc</a>.

**Note:** historically, WALA has used Maven as its build system.
However, this WALA branch can also use Gradle as an alternative to
Maven.  See [the Gradle-specific README](README-Gradle.md) for more
instructions and helpful tips.
