## SHARP: Incremental Context-Sensitive Points-to Ananlysis 

This is a repository of the implementations of incremental points-to analysis for k-limiting.

### Incremental Algorithm for k-CFA
````edu.tamu.wala.increpta.kcfa```` 

### Incremental Algorithm for k-obj
````edu.tamu.wala.increpta.kobj```` (the implemented k-obj is at ````/src/edu/tamu/wala/newkobj/````)

#### How to Use
0. requires the following wala modules: 
````
 com.ibm.wala.util,
 com.ibm.wala.core,
 com.ibm.wala.shrike,
 com.ibm.wala.cast, 
 com.ibm.wala.cast.java
````
1. git clone this repository 
2. import all projects (wala modules and this repositories) into a workspace of Eclipse
3. for the analyzed git repo, create a yml file as follow: 

````yml
name: MyGitHubRepoExample
branches: [ master ]  // the branch you want to analyze
build:
    runs_on: java1.8 // which java version to compile
    steps:

      - name: Run // a sequence of command to compile the project
        run: |
          mkdir bin
          javac -source 1.8 -target 1.8 MyGitHubRepoExample.java -d bin

d4tasks: 

  - name: Do_Main // set a task name
    which_main_class: MyGitHubRepoExample  // the main method you want to set as entry point
    classes: /bin // the relative path of compiled class files
    exclusions:  // the list of libraries to exclude from this analysis
      java/applet/.*,
      java/awt/.*,
      java/beans/.*,
      java/io/.*,
      java/math/.*,
      java/net/.*,
      java/nio/.*,
      java/rmi/.*,
      java/security/.*,
      java/sql/.*,
      java/text/.*,
      java/util/.*   
````

this example yml file is at ````/Incremental_Points_to_Analysis/Sharp/edu.tamu.wala.full.tests/ymls/april1989/MyGitHubAppExample/d4config.yml````, and make sure to use the correct github username and repo name in the path.

4. edit the fields in the file ````GitPtrTest.java```` under ````edu.tamu.wala.full.tests/src/```` to run for the git repo (comments are provided to illustrate each field)


