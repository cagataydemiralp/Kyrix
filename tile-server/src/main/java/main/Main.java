package main;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import index.Indexer;
import project.Project;
import server.Server;
import cache.TileCache;

import javax.script.ScriptException;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

import org.scidb.jdbc.Connection;
//import org.scidb.jdbc.IResultSetWrapper;

public class Main {

    private static Project project = null;
    public static String projectJSON = "";

    public static void main(String[] args) throws IOException,
            ClassNotFoundException,
            SQLException,
            ScriptException,
            NoSuchMethodException, InterruptedException {

	// connect to scidb
	connectScidb();
/*
        // read config file
        readConfigFile();

        // get project definition, create project object
        getProjectObject();

        // if project object is not null and is dirty, precompute
        if (project != null && isProjectDirty()) {
            System.out.println("Main project definition has been changed since last session, re-calculating indexes...");
            Indexer indexer = new Indexer();
            indexer.precompute();
            setProjectClean();
        }
        else if (project != null)
            System.out.println("Main project definition has not been changed since last session. Starting server right away...");

        //cache
        TileCache.create();

        // start server
        Server.startServer(Config.portNumber);
  */  }

    public static Project getProject() {
        return project;
    }

    public static void setProject(Project newProject) {

        project = newProject;
    }

    public static boolean isProjectDirty() throws SQLException, ClassNotFoundException {

        String sql = "select dirty from " + Config.projectTableName + " where name = \'" + Config.projectName + "\';";
        ArrayList<ArrayList<String>> ret = DbConnector.getQueryResult(Config.databaseName, sql);
        return (Integer.valueOf(ret.get(0).get(0)) == 1 ? true : false);
    }

    public static void setProjectClean() throws SQLException, ClassNotFoundException {

        String sql = "update " + Config.projectTableName + " set dirty = " + 0 + " where name = \'" + Config.projectName + "\';";
        DbConnector.executeUpdate(Config.databaseName, sql);
        DbConnector.commitConnection(Config.databaseName);
    }

    private static void readConfigFile() throws IOException {

        // read config file
        BufferedReader br = new BufferedReader(new FileReader(Config.configFileName));
        String line;
        List<String> inputStrings = new ArrayList<>();
        while ((line = br.readLine()) != null)
            inputStrings.add(line);

        Config.projectName = inputStrings.get(Config.projectNameRow);
        Config.portNumber = Integer.valueOf(inputStrings.get(Config.portNumberRow));
        Config.database = (inputStrings.get(Config.dbRow).toLowerCase().equals("mysql") ?
                Config.Database.MYSQL : Config.Database.PSQL);
        Config.dbServer = inputStrings.get(Config.dbServerRow);
        Config.userName = inputStrings.get(Config.userNameRow);
        Config.password = inputStrings.get(Config.passwordRow);
        Config.databaseName = inputStrings.get(Config.kyrixDbNameRow);
        Config.d3Dir = inputStrings.get(Config.d3DirRow);
    }

    private static void getProjectObject() throws ClassNotFoundException, SQLException {

        String sql = "select content from " + Config.projectTableName + " where name = \'" + Config.projectName + "\';";
        try {
            ArrayList<ArrayList<String>> ret = DbConnector.getQueryResult(Config.databaseName, sql);
            projectJSON = ret.get(0).get(0);
            Gson gson = new GsonBuilder().create();
            project = gson.fromJson(projectJSON, Project.class);
        } catch (Exception e) {
            System.out.println("Cannot find definition of main project... waiting...");
        }
        DbConnector.commitConnection(Config.databaseName);
    }
    private static void connectScidb() throws IOException {
	try {
            Class.forName("org.scidb.jdbc.Driver");
        }
	catch (ClassNotFoundException e)
        {
            System.out.println("Driver is not in the CLASSPATH -> " + e);
        }
	String url = "jdbc:scidb://127.0.0.1:1239/";
	try{
//	Connection dbConn = DriverManager.getConnection(url);
	Connection dbConn = new Connection("127.0.0.1",1239);
	Statement st = dbConn.createStatement();
	dbConn.getSciDBConnection().setAfl(false);
	ResultSet res = st.executeQuery("select * from test");
//	ResultSet res = st.executeQuery("select * from array(<a:string>[x=0:2,3,0, y=0:2,3,0], '[[\"a\",\"b\",\"c\"][\"d\",\"e\",\"f\"][\"123\",\"456\",\"789\"]]')");
        ResultSetMetaData meta = res.getMetaData();
    	System.out.println("Source array name: " + meta.getTableName(0));
        System.out.println(meta.getColumnCount() + " columns:");
	}
	catch (SQLException e)
        {
            System.out.println("scidb error:" + e);
        }
    }
}
