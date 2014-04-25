package org.aksw.jdbc_utils.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang.StringEscapeUtils;

import com.google.common.base.Joiner;



public class Inserter {
	private ColumnsReference target;
	private Schema schema;
	
	// Somehow I regret using a linear array for storing tabular data
	// it makes the code so convoluted for a bit of potential performance gain...
	private List<Object> data = new ArrayList<Object>(); 
	
	
	
	public Inserter(ColumnsReference target, Schema schema) {
		this.target = target;
		this.schema = schema;
	}
	
	//private List<List<String>> uniqueColumns;

	/*
	void check() {
		PrimaryKey pk;
		
		if()
		pk.getSource().getColumnNames()
	}
	*/
	
	public void add(Object ... cells) {
		if(cells.length != target.getColumnNames().size()) {
			throw new RuntimeException("Provided cells (" + cells.length + ") does not match number of columns (" + target.getColumnNames().size() + "), Columns:" + target.getColumnNames() + " Data: " + cells);
		}

		for(Object cell : cells) {
			data.add(cell);
		}
	}
	
	
	public static String escapeSql(Object o) {
		if(o == null) {
			return "NULL";
		} else if(o instanceof Number) {
			return "" + o;
		} else{
			return "'" + StringEscapeUtils.escapeSql("" + o) + "'";
		}
	}


	public String composeValues(List<Object> cells, int columnWidth, int[] idMap, List<Integer> indexes) {
	
		int numRows = indexes == null ? data.size() / columnWidth : indexes.size();

		String idList = "";
		for(int i = 0; i < numRows; ++i) {

		    int rowId = indexes == null ? i : indexes.get(i);
		    
			if(rowId != 0) {
				idList += ", ";
			}
			
			
			if(columnWidth > 1) {
			    idList += "(";
			}
			
			for(int j = 0; j < idMap.length; ++j) {
				int index = rowId * columnWidth + idMap[j];
				Object cell = data.get(index);
			
				if(j != 0) {
					idList += ", ";
				}
				
				idList += escapeSql("" + cell);
			}

			if(columnWidth > 1) {
                idList += ")";
            }

		}

		return idList;
	}
	
	public List<Integer> getBlacklistedRows(List<Object> cells, int columnWidth, int[] idMap, Set<Object> ids) {
	    
	    /*
	    List<Class<?>> types = new ArrayList<Class<?>>(ids.size());
	    for(Object id : ids) {
	        types.add(id == null ? null : id.getClass());
	    }
	    System.out.println(types);
	    */
	    
		List<Integer> result = new ArrayList<Integer>();
		
		int numRows = data.size() / columnWidth;
		for(int i = 0; i < numRows; ++i) {

			for(int j = 0; j < idMap.length; ++j) {
				int index = i * columnWidth + idMap[j];
				Object cell = data.get(index);
				
				if(ids.contains(cell)) {
					result.add(i);
				}
			}
			
		}
		
		return result;
	}
	
	public String composeInsertPart(List<Object> cells, int columnWidth, Set<Integer> blacklistedRows) {
		
		// TODO Only works with columnWidth 1
		
		int numRows = data.size() / columnWidth;
		String idList = "";
		for(int i = 0; i < numRows; ++i) {
			
			if(blacklistedRows.contains(i)) {
				continue;
			}
			
			if(!idList.isEmpty()) {
				idList += ", ";
			}
			
			idList += "(";
			
			for(int j = 0; j < columnWidth; ++j) {
				int index = i * columnWidth + j;
				Object cell = data.get(index);

				if(j != 0) {
					idList += ", ";
				}
				
				idList += escapeSql("" + cell);
			}
			
			idList += ")";
		}

		return idList;
	}
	
	
	
	public void flush(Connection conn) throws SQLException {
		PrimaryKey targetPk = schema.getPrimaryKeys().get(target.getTableName());
		
		int columnWidth = target.getColumnNames().size();
		
		Set<Object> duplicateIds = new HashSet<Object>();
		//List<Integer> blacklistedRows = Collections.emptySet();

		
        String targetColumnsStr = "\"" + Joiner.on("\", \"").join(target.getColumnNames()) + "\"";

        
		if(targetPk != null) {
			List<String> idColumns = targetPk.getSource().getColumnNames();			

			
			int d = idColumns.size();
			
			int idMap[] = new int[d];
			
			for(int i = 0; i < d; ++i) {
				String idColumn = idColumns.get(i);
				int j = target.getColumnNames().indexOf(idColumn);
				
				if(j < 0) {
					throw new RuntimeException("Need all primary key columns: Inserted Columns " + target.getColumnNames() + ", Primary Key: " + targetPk.getSource().getColumnNames());
				}
				
				idMap[i] = j;				
			}

			
            String idColumnsStr = "\"" + Joiner.on("\", \"").join(idColumns) + "\"";
            
            
            String inColumnsStr = columnWidth > 1 ? "(" + idColumnsStr + ")" : idColumnsStr;
			
//			if(d != 1) {
//				throw new RuntimeException("Only single column primary keys supported right now - Sorry :(");
//			}
			
			
//			String idColumn = idColumns.get(0);

			String idList = composeValues(data, columnWidth, idMap, null); 
			List<Object> dupList;
			{
				// TODO Add a switch so we additionally fetch the values in the DB
				// So we can present the user with the existing values for conflicting entries
				
				if(!idList.isEmpty()) {
				    
					String query = "SELECT " + idColumnsStr + " FROM \"" + target.getTableName() + "\" WHERE " + inColumnsStr + " IN (" + idList + ")";
					System.out.println("Dup check: " + query);
					
					dupList = SqlUtils.executeList(conn, query, Object.class);
					duplicateIds.addAll(dupList);
				
					System.out.println("Dups are: " + duplicateIds);
				}
			}

			/*
	        {
	            //composeCheckPart(data, columnWidth, idMap); 
	            String idListStr = Joiner.on(", ").join(dupList) 
	            String query = "DELETE FROM \"" + target.getTableName() + "\" WHERE \"" + idColumnsStr + "\" IN (" + idList + ")";
	        }
	        */


			List<Integer> blacklistedRows = getBlacklistedRows(data, columnWidth, idMap, duplicateIds);

			if(blacklistedRows.size() > 0) {
                String deleteIdList = composeValues(data, columnWidth, idMap, blacklistedRows); 
    
                String query = "DELETE FROM \"" + target.getTableName() + "\" WHERE " + inColumnsStr + " IN (" + idList + ")";
                System.out.println("Delete query: " + query);
                
                SqlUtils.execute(conn, query, Void.class);
			}
			
		}
		
		

		Set<Integer> skipIndexes = Collections.emptySet();
		String valueList = composeInsertPart(data, columnWidth, skipIndexes); 

		
		{
			if(!valueList.isEmpty()) {
				String query = "INSERT INTO \"" + target.getTableName() + "\" (" + targetColumnsStr + ") VALUES " + valueList;
				System.out.println("Insert: " + query);
				SqlUtils.execute(conn, query, Void.class);
			}
		}
		
		
		data.clear();

		
		
	}
}
