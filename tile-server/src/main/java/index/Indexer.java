package index;

import com.coveo.nashorn_modules.FilesystemFolder;
import com.coveo.nashorn_modules.Require;
import jdk.nashorn.api.scripting.JSObject;
import jdk.nashorn.api.scripting.NashornScriptEngine;
import main.Config;
import main.DbConnector;
import main.Main;
import project.*;


import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by wenbo on 1/12/18.
 */
public class Indexer {

	private Project project;
	private Statement bboxStmt;
	private Statement tileStmt;

	public Indexer() throws SQLException, 
	       ClassNotFoundException, 
	       ScriptException, 
	       NoSuchMethodException {

		project = Main.getProject();
		bboxStmt = DbConnector.getStmtByDbName(Config.databaseName);
		tileStmt = DbConnector.getStmtByDbName(Config.databaseName);

	}


	public void precompute() throws SQLException,
			ClassNotFoundException,
			ScriptException,
			NoSuchMethodException {

		// if (true) return;

		System.out.println("Precomputing...");

		String projectName = project.getName();

		System.out.println("Project name:" + projectName);
		System.out.println("Bbox batch size:" + Config.bboxBatchSize);
		System.out.println("Tile batch size:" + Config.tileBatchSize);


		if (Config.database == Config.Database.PSQL){

			String psql = "CREATE EXTENSION if not exists postgis;";
			bboxStmt.executeUpdate(psql);
			psql = "CREATE EXTENSION if not exists postgis_topology;";
			bboxStmt.executeUpdate(psql);

		} else if (Config.database == Config.Database.VSQL){
			// Vertica supports spatial index creation  out of the box, kind of
			System.out.println("Vertica supports spatial indexes natively...");
		}

		// for each canvas and for each layer
		// Step 0, create a bbox table and tile table
		// Step 1, set up nashorn environment
		// Step 2, for each tuple in the query result
		// Step 3,     run data transforms to get transformed tuple
		// Step 4,     calculate bounding box
		// Step 5,     insert this tuple, its bbox and mappings
		// Step 6, create indexes (spatial index, index on tuple_id, and secondary indexes)
		for (Canvas c : project.getCanvases())
			for (int layer_id = 0; layer_id < c.getLayers().size(); layer_id ++) {

				Layer l = c.getLayers().get(layer_id);
				Transform trans = c.getTransformById(l.getTransformId());

				// step 0: create tables for storing bboxes and tiles
				String bboxTableName = "bbox_" + projectName + "_" + c.getId() + "layer" + layer_id;
				String tileTableName = "tile_" + projectName + "_" + c.getId() + "layer" + layer_id;

				// drop table if exists
				String sql = "drop table if exists " + bboxTableName + ";";
				bboxStmt.executeUpdate(sql);
				sql = "drop table if exists " + tileTableName + ";";
				tileStmt.executeUpdate(sql);
				sql = "drop table if exists sorted_" + tileTableName + ";";
				tileStmt.executeUpdate(sql);

				// create the bbox table
				sql = "create table " + bboxTableName + " (";

				for (int i = 0; i < trans.getColumnNames().size(); i ++)
					if (Config.database == Config.Database.PSQL)
						sql += trans.getColumnNames().get(i) + " text, ";
					else if (Config.database == Config.Database.VSQL)
						sql += trans.getColumnNames().get(i) + " varchar, ";
					else if (Config.database == Config.Database.MYSQL)
						sql += trans.getColumnNames().get(i) + " mediumtext, ";


				if (Config.database == Config.Database.PSQL){

					if (Config.indexingScheme == Config.IndexingScheme.TUPLE_MAPPING ||
							Config.indexingScheme == Config.IndexingScheme.SORTED_TUPLE_MAPPING) {
						sql += "tuple_id int, ";
					}

					sql += "cx double precision, cy double precision, minx double precision, miny double precision, maxx double precision, maxy double precision, geom geometry(polygon)";

				} else if(Config.database == Config.Database.VSQL){

					// Vertica needs a geometry id to create spatial indexes
					sql += "tuple_id int, ";
					sql += "cx double precision, cy double precision, minx double precision, miny double precision, maxx double precision, maxy double precision, geom geometry(160)";

				} else if (Config.database == Config.Database.MYSQL) {

					if (Config.indexingScheme == Config.IndexingScheme.TUPLE_MAPPING ||
							Config.indexingScheme == Config.IndexingScheme.SORTED_TUPLE_MAPPING) {
						sql += "tuple_id int, ";
					}

					sql += "cx double precision, cy double precision, minx double precision, miny double precision, maxx double precision, maxy double precision, geom polygon not null";

					if (Config.indexingScheme == Config.IndexingScheme.TUPLE_MAPPING ||
							Config.indexingScheme == Config.IndexingScheme.SORTED_TUPLE_MAPPING) {
						sql += ", index (tuple_id)";
					}

					if (Config.indexingScheme == Config.IndexingScheme.SPATIAL_INDEX) {
						sql += ", spatial index (geom)";
					}

				}

				sql += ");";
				bboxStmt.executeUpdate(sql);

				// create tile table
				if (Config.indexingScheme == Config.IndexingScheme.TUPLE_MAPPING ||
						Config.indexingScheme == Config.IndexingScheme.SORTED_TUPLE_MAPPING) {
					if (Config.database == Config.Database.PSQL)
						sql = "create table " + tileTableName + " (tuple_id int, tile_id varchar(50));";
					else if (Config.database == Config.Database.VSQL)
						sql = "create table " + tileTableName + " (tuple_id int, tile_id varchar(50));";
					else if (Config.database == Config.Database.MYSQL)
						sql = "create table " + tileTableName + " (tuple_id int, tile_id varchar(50), index (tile_id));";
					tileStmt.executeUpdate(sql);
				}

				// if this is an empty layer, continue
				if (trans.getDb().equals(""))
					continue;

				// step 1: set up nashorn environment, prepared statement, column name to id mapping
				NashornScriptEngine engine = (NashornScriptEngine) new ScriptEngineManager()
						.getEngineByName("nashorn");
				FilesystemFolder rootFolder = FilesystemFolder.create(new File(Config.d3Dir), "UTF-8");
				Require.enable(engine, rootFolder);

				// step 1(a): register the data transform function with nashorn
				String script = "var d3 = require('d3');\n"; // TODO: let users specify all required d3 libraries.
				script += "var trans = " + trans.getTransformFunc() + ";\n";
				engine.eval(script);

				// step 1(b): get rendering parameters
				engine.put("renderingParams", project.getRenderingParams());
				JSObject renderingParamsObj = (JSObject) engine.eval("JSON.parse(renderingParams)");

				// step 1(c): construct a column name to column index mapping table
				Map<String, Integer> colName2Id = new HashMap<>();
				for (int i = 0; i < trans.getColumnNames().size(); i++){
					colName2Id.put(trans.getColumnNames().get(i), i);
				}

				// step 1(d): extract placement stuff
				Placement p = (l.isStatic() ? null : l.getPlacement());
				String centroid_x = (l.isStatic() ? null : p.getCentroid_x());
				String centroid_y = (l.isStatic() ? null : p.getCentroid_y());
				String width_func = (l.isStatic() ? null : p.getWidth());
				String height_func = (l.isStatic() ? null : p.getHeight());

				//
				// step 2: looping through query results
				// TODO: distinguish between separable and non-separable cases
				//

				//
				// Only one database can be up on a Vertica server
				// This means that user data and Kyrix tables  will be on the
				// same database and we need two separate connections to
				// read from and write to it, respectively.
				//
				String key;
				ResultSet rs;
				if(Config.database == Config.Database.VSQL){
					key = Config.databaseName+"_reader";
					//create a second connnection by key
					rs = DbConnector.getQueryResultIteratorByKey(key,
							Config.databaseName,
							trans.getQuery());
				}else{
					rs = DbConnector.getQueryResultIterator(trans.getDb(), trans.getQuery());
				}

				int numColumn = rs.getMetaData().getColumnCount();
				int rowCount = 0, mappingCount = 0;

				String headerPrefix;
				String headerSuffix;
				String rowPrefix;
				String rowSuffix;
				String firstRowPrefix;
				String lastRowSuffix;

				if (Config.database == Config.Database.VSQL){

					headerPrefix = "insert /*+direct*/ into ";
					headerSuffix = "";
					//'union all' instead of 'union' avoids the overhead for duplicate removal
					rowPrefix = " union all select ";
					rowSuffix = " ";
					firstRowPrefix = " select ";
					lastRowSuffix = "";

				} else { // psql

					headerPrefix = "insert into ";
					headerSuffix = " values";
					rowPrefix = ",(";
					rowSuffix = ")";
					firstRowPrefix ="(";
					lastRowSuffix = ")";

				}

				StringBuilder bboxInsSqlBuilder = new StringBuilder(headerPrefix +
						bboxTableName + headerSuffix);

				StringBuilder tileInsSqlBuilder = new StringBuilder(headerPrefix +
						tileTableName +
						headerSuffix);


				while (rs.next()) {

					rowCount ++;
					if (rowCount % 1000000 == 0) {
						System.out.println(rowCount);
					}

					//get raw row
					ArrayList<String> curRawRow = new ArrayList<>();
					for (int i = 1; i <= numColumn; i ++)
						curRawRow.add(rs.getString(i));

					// step 3: run transform function on this tuple
					String[] transformedStrArray = (String[]) engine	// TODO: figure out why row.slice does not work. learn more about nashorn types
							.invokeFunction("trans", curRawRow, c.getW(), c.getH(), renderingParamsObj);
					ArrayList<String> transformedRow = new ArrayList<>();
					for (int i = 0; i < transformedStrArray.length; i++)
						transformedRow.add(transformedStrArray[i].toString());

					// step 4: calculate bounding boxes
					ArrayList<Double> curBbox = new ArrayList<>();
					if (! l.isStatic()) {
						double centroid_x_dbl, centroid_y_dbl;
						double width_dbl, height_dbl;

						// centroid_x
						if (centroid_x.substring(0, 3).equals("con")) {
							centroid_x_dbl = Double.parseDouble(centroid_x.substring(4));
						} else {
							String curColName = centroid_x.substring(4);
							int curColId = colName2Id.get(curColName);
							centroid_x_dbl = Double.parseDouble(transformedRow.get(curColId));
						}

						// centroid_y
						if (centroid_y.substring(0, 3).equals("con")) {
							centroid_y_dbl = Double.parseDouble(centroid_y.substring(4));
						} else {
							String curColName = centroid_y.substring(4);
							int curColId = colName2Id.get(curColName);
							centroid_y_dbl = Double.parseDouble(transformedRow.get(curColId));
						}

						// width
						if (width_func.substring(0, 3).equals("con")) {
							width_dbl = Double.parseDouble(width_func.substring(4));
						} else {
							String curColName = width_func.substring(4);
							int curColId = colName2Id.get(curColName);
							width_dbl = Double.parseDouble(transformedRow.get(curColId));
						}

						// height
						if (height_func.substring(0, 3).equals("con")) {
							height_dbl = Double.parseDouble(height_func.substring(4));
						} else {
							String curColName = height_func.substring(4);
							int curColId = colName2Id.get(curColName);
							height_dbl = Double.parseDouble(transformedRow.get(curColId));
						}

						// get bounding box
						curBbox.add(centroid_x_dbl);	// cx
						curBbox.add(centroid_y_dbl);	// cy
						curBbox.add(centroid_x_dbl - width_dbl / 2.0);	// min x
						curBbox.add(centroid_y_dbl - height_dbl / 2.0);	// min y
						curBbox.add(centroid_x_dbl + width_dbl / 2.0);	// max x
						curBbox.add(centroid_y_dbl + height_dbl / 2.0);	// max y

					} else {

						for (int i = 0; i < 6; i++) {
							curBbox.add(0.0);
						}

					}


					if (rowCount % Config.bboxBatchSize ==  1) {
						bboxInsSqlBuilder.append(firstRowPrefix);
					} else {
						bboxInsSqlBuilder.append(rowPrefix);
					}


					for (int i = 0; i < transformedRow.size(); i ++) {

						if (Config.database == Config.Database.PSQL) {
							bboxInsSqlBuilder.append("'" + transformedRow.get(i).replaceAll("\'", "\'\'") + "', ");
						} else if (Config.database == Config.Database.VSQL) {
							bboxInsSqlBuilder.append("'" + transformedRow.get(i).replaceAll("\'", "\'\'") + "', ");
						} else if (Config.database == Config.Database.MYSQL){
							bboxInsSqlBuilder.append("'" + transformedRow.get(i).replaceAll("\'", "\\\\'") + "', ");
						}
					}

					if (Config.database == Config.Database.VSQL ||
							Config.indexingScheme !=  Config.IndexingScheme.SPATIAL_INDEX) {
						bboxInsSqlBuilder.append(String.valueOf(rowCount) + ", ");
					}

					for (int i = 0; i < 6; i ++) {
						bboxInsSqlBuilder.append(String.valueOf(curBbox.get(i)) + ", ");
					}

					double minx, miny, maxx, maxy;
					minx = curBbox.get(2);
					miny = curBbox.get(3);
					maxx = curBbox.get(4);
					maxy = curBbox.get(5);
					bboxInsSqlBuilder.append("ST_GeomFromText('Polygon((");
					bboxInsSqlBuilder.append(String.valueOf(minx) + " " +
							String.valueOf(miny) + "," + String.valueOf(maxx) + " " +
							String.valueOf(miny) + "," + String.valueOf(maxx) + " " +
							String.valueOf(maxy) + "," + String.valueOf(minx) + " " +
							String.valueOf(maxy) + "," + String.valueOf(minx) + " " +
							String.valueOf(miny));

					bboxInsSqlBuilder.append("))')");

					if (rowCount % Config.bboxBatchSize == 0) {

						bboxInsSqlBuilder.append(lastRowSuffix + ";");
						bboxStmt.executeUpdate(bboxInsSqlBuilder.toString());
						DbConnector.commitConnection(Config.databaseName);
						bboxInsSqlBuilder = new StringBuilder(headerPrefix + bboxTableName + headerSuffix);
					}

					// insert into tile table
					if (! l.isStatic() &&
							Config.indexingScheme == Config.IndexingScheme.TUPLE_MAPPING ||
							Config.indexingScheme == Config.IndexingScheme.SORTED_TUPLE_MAPPING ) {

						int xStart = (int) Math.max(0, Math.floor(minx / Config.tileW));
						int yStart = (int) Math.max(0, Math.floor(miny/ Config.tileH));
						int xEnd = (int) Math.floor(maxx / Config.tileW);
						int yEnd = (int) Math.floor(maxy / Config.tileH);

						for (int i = xStart; i <= xEnd; i ++) {
							for (int j = yStart; j <= yEnd; j++) {

								mappingCount++;

								String tileId = (i * Config.tileW) + "_" + (j * Config.tileH);

								if (mappingCount == 1) {
									tileInsSqlBuilder.append(firstRowPrefix);
								} else {
									tileInsSqlBuilder.append(rowPrefix);
								}

								tileInsSqlBuilder.append(rowCount + ", " + "'" + tileId + "'");

								if (mappingCount % Config.tileBatchSize == 0) {

									tileInsSqlBuilder.append(lastRowSuffix + ";");
									tileStmt.executeUpdate(tileInsSqlBuilder.toString());
									DbConnector.commitConnection(Config.databaseName);
									tileInsSqlBuilder = new StringBuilder(headerPrefix + tileTableName + headerSuffix);

								}

							}
						}
					}


				}

				rs.close();

				// insert tail stuff
				if (rowCount % Config.bboxBatchSize != 0) {

					bboxInsSqlBuilder.append(lastRowSuffix + ";");
					bboxStmt.executeUpdate(bboxInsSqlBuilder.toString());
					DbConnector.commitConnection(Config.databaseName);

					System.out.println("*");
				}


				if (mappingCount % Config.tileBatchSize != 0) {

					tileInsSqlBuilder.append(lastRowSuffix+";");
					tileStmt.executeUpdate(tileInsSqlBuilder.toString());
					DbConnector.commitConnection(Config.databaseName);

				}

				if (Config.database == Config.Database.PSQL) {

					if (Config.indexingScheme == Config.IndexingScheme.TUPLE_MAPPING ||
							Config.indexingScheme == Config.IndexingScheme.SORTED_TUPLE_MAPPING) {
						sql = "create index tuple_idx on " + bboxTableName + " (tuple_id);";
						bboxStmt.executeUpdate(sql);
						sql = "create index tile_idx on "  + tileTableName + " (tile_id);";
						tileStmt.executeUpdate(sql);
					}

					if (Config.indexingScheme == Config.IndexingScheme.SPATIAL_INDEX) {
						sql = "create index sp_" + bboxTableName + " on " + bboxTableName + " using gist (geom);";
						bboxStmt.executeUpdate(sql);
						sql = "cluster " + bboxTableName + " using sp_" + bboxTableName + ";";
						bboxStmt.executeUpdate(sql);
					} if (Config.indexingScheme == Config.IndexingScheme.TUPLE_MAPPING) {

						sql = "cluster " + bboxTableName + " using tuple_idx;";
						tileStmt.executeUpdate(sql);
						sql = "cluster " + tileTableName + " using tile_idx;";
						tileStmt.executeUpdate(sql);

					}

				} else if (Config.database == Config.Database.VSQL) {


					if (Config.indexingScheme == Config.IndexingScheme.TUPLE_MAPPING ||
							Config.indexingScheme == Config.IndexingScheme.SORTED_TUPLE_MAPPING) {

						System.out.println("No indexes in Vertica. Sort?");
						//
						// create projections ?
						//
						// sql = "create index tuple_idx on " + bboxTableName + " (tuple_id);";
						// bboxStmt.executeUpdate(sql);
						// sql = "create index tile_idx on " + tileTableName + " (tile_id);";
						// tileStmt.executeUpdate(sql);
					}

					if (Config.indexingScheme == Config.IndexingScheme.SPATIAL_INDEX) {

						String indexName = "sp_"+bboxTableName;
						sql = "select STV_Create_Index(tuple_id, geom USING PARAMETERS index=\'"+indexName+"\', overwrite=\'true') OVER() from "+bboxTableName+";";
						bboxStmt.executeQuery(sql);

					}


				} else if (Config.database == Config.Database.MYSQL) {

					if (Config.indexingScheme == Config.IndexingScheme.SORTED_TUPLE_MAPPING) {

						sql = "create table sorted_" + tileTableName + " (tuple_id int, tile_id varchar(50));";
						tileStmt.executeUpdate(sql);
						sql = "insert into sorted_" + tileTableName + " select * from " + tileTableName + " order by tile_id;";
						tileStmt.executeUpdate(sql);
						sql = "alter table sorted_" + tileTableName + " add index(tile_id);";
						tileStmt.executeUpdate(sql);

					}

				}

				DbConnector.commitConnection(Config.databaseName);
			}

		bboxStmt.close();
		tileStmt.close();
		System.out.println("Done precomputing!");
	}
}
