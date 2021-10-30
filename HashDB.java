//Script to look up API functions in HashDB (https://hashdb.openanalysis.net/)
//@author @larsborn @huettenhain
//@category HashDB
//@keybinding F3
//@menupath 
//@toolbar 

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import ghidra.app.decompiler.DecompilerLocation;
import ghidra.app.script.GhidraScript;
import ghidra.app.tablechooser.AddressableRowObject;
import ghidra.app.tablechooser.StringColumnDisplay;
import ghidra.app.tablechooser.TableChooserDialog;
import ghidra.app.tablechooser.TableChooserExecutor;

import ghidra.framework.plugintool.PluginTool;
import ghidra.program.util.OperandFieldLocation;
import ghidra.util.task.TaskMonitor;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import docking.widgets.checkbox.GCheckBox;
import docking.widgets.label.GDLabel;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.CategoryPath;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.DataTypeConflictHandler;
import ghidra.program.model.data.DataTypeManager;
import ghidra.program.model.data.DataTypePath;
import ghidra.program.model.data.EnumDataType;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.Program;
import ghidra.program.model.pcode.Varnode;
import ghidra.program.model.scalar.Scalar;

import java.net.URL;
import java.security.SecureRandom;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingWorker;

public class HashDB extends GhidraScript {
	boolean HTTP_DEBUGGING = true;
	boolean GUI_DEBUGGING = false;

	private class HashDBApi {
		private String baseUrl = "https://hashdb.openanalysis.net";

		private class Hashes {
			@SuppressWarnings({ "unused" })
			public long[] hashes;

			public Hashes(long[] hashes) {
				this.hashes = hashes;
			}
		}

		private ArrayList<String> hunt(long[] hashes) throws Exception {
			ArrayList<String> ret = new ArrayList<String>();
			JsonObject response = JsonParser
					.parseString(httpQuery("POST", "hunt", new Gson().toJson(new Hashes(hashes)).getBytes()))
					.getAsJsonObject();
			for (JsonElement hit : response.get("hits").getAsJsonArray()) {
				ret.add(hit.getAsJsonObject().get("algorithm").getAsString());
			}

			return ret;
		}

		public class HashInfo {
			public long hash;
			public String apiName;
			public String permutation;
			public String modules[];

			public HashInfo(long hash, String apiName, String permutation, String modules[]) {
				this.hash = hash;
				this.apiName = apiName;
				this.permutation = permutation;
				this.modules = modules;
			}
		}

		private ArrayList<HashInfo> parseHashInfoFromJson(String httpResponse) {
			JsonObject response = JsonParser.parseString(httpResponse).getAsJsonObject();
			ArrayList<HashInfo> ret = new ArrayList<HashInfo>();
			for (JsonElement hashEntry : response.get("hashes").getAsJsonArray()) {
				JsonObject hashObject = hashEntry.getAsJsonObject();
				JsonObject stringInfo = hashObject.get("string").getAsJsonObject();
				JsonArray modulesArray = stringInfo.get("modules").getAsJsonArray();

				String[] modules = new String[modulesArray.size()];
				for (int i = 0; i < modules.length; i++) {
					modules[i] = modulesArray.get(i).getAsString();
				}
				if (!stringInfo.get("is_api").getAsBoolean()) {
					continue;
				}
				ret.add(new HashInfo(hashObject.get("hash").getAsLong(), stringInfo.get("api").getAsString(),
						stringInfo.get("permutation").getAsString(), modules));
			}
			return ret;
		}

		private ArrayList<HashInfo> resolve(String algorithm, long hash) throws Exception {
			ArrayList<HashInfo> ret = parseHashInfoFromJson(
					httpQuery("GET", String.format("hash/%s/%d", algorithm, hash)));
			for (HashInfo hashInfo : ret) {
				if (hashInfo.hash != hash) {
					throw new Exception("hash mismatch");
				}
			}
			return ret;
		}

		private ArrayList<HashInfo> module(String module, String algorithm, String permutation) throws Exception {
			return parseHashInfoFromJson(
					httpQuery("GET", String.format("module/%s/%s/%s", module, algorithm, permutation)));
		}

		private String httpQuery(String method, String endpoint) throws Exception {
			return httpQuery(method, endpoint, null);
		}

		private String httpQuery(String method, String endpoint, byte[] postData) throws Exception {
			String urlString = String.format("%s/%s", baseUrl, endpoint);
			if (HTTP_DEBUGGING) {
				println(String.format("[HashDB] %s %s", method, urlString));
			}
			URL url = new URL(urlString);
			SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
			sslContext.init(null, null, new SecureRandom());
			HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
			conn.setSSLSocketFactory(sslContext.getSocketFactory());

			conn.setInstanceFollowRedirects(true);
			conn.setDoOutput(true);
			conn.setRequestMethod(method);
			conn.setUseCaches(false);
			if (postData != null) {
				conn.setRequestProperty("Content-Type", "application/json; utf-8");
				conn.setRequestProperty("Content-Length", Integer.toString(postData.length));
				try (OutputStream wr = conn.getOutputStream()) {
					wr.write(postData);
				}
			}

			try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"))) {
				StringBuilder response = new StringBuilder();
				String responseLine = null;
				while ((responseLine = br.readLine()) != null) {
					response.append(responseLine.trim());
				}
				if (HTTP_DEBUGGING) {
					println(String.format("[HashDB] HTTP Response: %s", response));
				}
				return response.toString();
			}
		}
	}

	private class HashTableExecutor implements TableChooserExecutor {
		public HashTableExecutor() {

		}

		@Override
		public String getButtonName() {
			return "Query!";
		}

		@Override
		public boolean execute(AddressableRowObject rowObject) {
			return false;
		}

	}

	class HashTable extends TableChooserDialog {
		private JTextField enumNameTextField;
		private JComboBox<String> transformationTextField;
		private JComboBox<String> hashAlgorithmField;
		private GCheckBox resolveModulesCheckbox;

		public HashTable(PluginTool tool, TableChooserExecutor executor, Program program, String title) {
			super(tool, executor, program, title, null, false);
		}

		@Override
		protected void setOkEnabled(boolean state) {
			return;
		}
		
		public void addNewHashAlgorithm(String algorithm, boolean selectIt) {
			boolean exists = false;
			if (getCurrentHashAlgorithm() == algorithm)
				exists = true;
			for (int k = 0; !exists && k < hashAlgorithmField.getItemCount(); k++) {
				String item = hashAlgorithmField.getItemAt(k);
				if (item == algorithm)
					exists = true;
			}
			if (!exists)
				hashAlgorithmField.addItem(algorithm);
			if (selectIt)
				hashAlgorithmField.setSelectedItem(algorithm);
		}

		public String getCurrentHashAlgorithm() {
			return hashAlgorithmField.getEditor().getItem().toString();
		}
		
		public String getTransformation() {
			return transformationTextField.getEditor().getItem().toString();
		}

		public String getEnumName() {
			return enumNameTextField.getText();
		}

		public boolean resolveEntireModules() {
			return resolveModulesCheckbox.isSelected();
		}

		@Override
		protected void okCallback() {
			TaskMonitor tm = getTaskMonitorComponent();
			if (getSelectedRows().length == 0)
				selectRows(IntStream.range(0, getRowCount()).toArray());
			ArrayList<HashLocation> hashes = getSelectedRowObjects()
					.stream().map(a -> (HashLocation) a)
					.collect(Collectors.toCollection(ArrayList::new));
			tm.initialize(hashes.size());
			showProgressBar("Querying HashDB", true, false, 0);
			
			final class Resolver extends SwingWorker<String, Object> {

				private final TaskMonitor taskMonitor;
				private final ArrayList<HashLocation> hashLocations;

				Resolver(ArrayList<HashLocation> hashLocations, TaskMonitor taskMonitor) {
					this.hashLocations = hashLocations;
					this.taskMonitor = taskMonitor;
				}

				@Override
				protected String doInBackground() throws Exception {
					try {
						return resolveHashes(hashLocations, taskMonitor);
					} catch (Exception e) {
						println(String.format("[HashDB] exception during resolution: %s\n", e.toString()));
						return "unexpected error during resolution, see log";
					}
				}

				@Override
				protected void done() {
					String resultText;
					try {
						resultText = get();
					} catch (InterruptedException | ExecutionException e) {
						resultText =  "unknown error during execution";
					}
					clearSelection();
					selectRows();
					hideTaskMonitorComponent();
					setStatusText(resultText);
				}
			}
				
			Resolver resolver = new Resolver(hashes, tm);
			resolver.execute();
		}
		
		public void parentOkCallback() {
			super.okCallback();
		}
		
		protected void addWorkPanel(JComponent hauptPanele) {
			int rowCount = 4;
			super.addWorkPanel(hauptPanele);

			JPanel columnPanel = new JPanel(new BorderLayout(10, 10));
			JPanel leftPanel = new JPanel(new GridLayout(rowCount, 1));
			JPanel rightPanel = new JPanel(new GridLayout(rowCount, 1));
			columnPanel.add(leftPanel, BorderLayout.WEST);
			columnPanel.add(rightPanel, BorderLayout.CENTER);

			leftPanel.add(new GDLabel("Enum Name:"));
			enumNameTextField = new JTextField("HashDBEnum");
			rightPanel.add(enumNameTextField);

			leftPanel.add(new GDLabel("Hash Transformation:"));
			transformationTextField = new JComboBox<>();
			transformationTextField.setEditable(true);
			transformationTextField.addItem("X /* Unaltered Hash Value */");
			transformationTextField.addItem("X ^ 0xBAADF00D /* XOR */");
			transformationTextField.addItem("((((X ^ 0x76C7) << 0x10) ^ X) ^ 0xADB9) & 0x1FFFFF /*REvil*/");
			transformationTextField.setSelectedIndex(0);			
			rightPanel.add(transformationTextField);

			leftPanel.add(new GDLabel("Hash Algorithm"));
			hashAlgorithmField = new JComboBox<>();
			hashAlgorithmField.setEditable(true);
			rightPanel.add(hashAlgorithmField);
			
			leftPanel.add(new GDLabel(""));
			resolveModulesCheckbox = new GCheckBox("Resolve Entire Modules");
			rightPanel.add(resolveModulesCheckbox);
			
			JPanel outerPanel = new JPanel(new BorderLayout());
			outerPanel.add(columnPanel, BorderLayout.NORTH);

			hauptPanele.add(outerPanel, BorderLayout.SOUTH);
		}
	}

	static HashTable dialog = null;

	private void showDialog() {
		if (dialog == null || !dialog.isVisible()) {
			println("[HashDB] Creating new dialog.");
			dialog = new HashTable(state.getTool(), new HashTableExecutor(), currentProgram, "HashDB is BestDB");
			configureTableColumns(dialog);
		}
		state.getTool().showDialog(dialog);
	}

	private boolean addHash(long hash) {
		HashLocation newRow = new HashLocation(currentAddress, hash);
		dialog.add(newRow);
		return true;
	}

	public void run() throws Exception {
		boolean autoResolveNewHashes = false;
		long hash;
		try {
			hash = getSelectedHash();
		} catch (Exception e) {
			println(String.format("[HashDB] Error: %s", e.getMessage()));
			return;
		}
		showDialog();
		if (addHash(hash) && autoResolveNewHashes) {
			println(String.format("[HashDB] Querying hash 0x%08x", hash));
			dialog.okCallback();
		}
	}

	private int onHashResolution(EnumDataType hashEnumeration, HashLocation hl, HashDB.HashDBApi.HashInfo hashInfo) {
		hl.resolution = hashInfo.apiName;
		try {
			hashEnumeration.add(hashInfo.apiName, hashInfo.hash);
			return 1;
		} catch (IllegalArgumentException e) {
			if (GUI_DEBUGGING) {
				println(
					String.format("[HashDB] could not add %s (%s) to %s: %s",
						hashInfo.apiName,
						hl.getHashValue(),
						hashEnumeration.getDisplayName(),
						e.toString()
				));
			}
			return 0;
		}
	}
	
	private String resolveHashes(ArrayList<HashDB.HashLocation> hashLocations, TaskMonitor tm) throws Exception {
		HashDBApi api = new HashDBApi();
		long[] hashes = new long[hashLocations.size()];
		for (int k = 0; k < hashLocations.size(); k++)
			hashes[k] = transformHash(hashLocations.get(k).getHashAsLong(), dialog.getTransformation());
		long taskTotal = tm.getMaximum();
		String algorithm = dialog.getCurrentHashAlgorithm();
		
		if (algorithm.isBlank()) {
			long taskHunt = taskTotal / 2;
			if (taskHunt < 1) {
				taskHunt = 1;
			}
			taskTotal += taskHunt;
			tm.setMaximum(taskTotal);
			tm.setMessage("guessing hash function");
			ArrayList<String> algorithms = api.hunt(hashes);
			if (algorithms.size() == 0) {
				return "could not identify any hashing algorithms";
			} else if (algorithms.size() == 1) {
				algorithm = algorithms.iterator().next();
				dialog.addNewHashAlgorithm(algorithm, true);
			} else {
				for (String a: algorithms)
					dialog.addNewHashAlgorithm(a, false);	
				return "please select an algorithm";				
			}
			tm.incrementProgress(taskHunt);
		}
		
		tm.setMessage(String.format("resolving hashes for algorithm %s", algorithm));

		int resolveCount = 0;
		String hashEnumName = dialog.getEnumName(); 
		DataTypeManager dataTypeManager = getCurrentProgram().getDataTypeManager();
		DataType existingDataType = dataTypeManager.getDataType(new DataTypePath("/HashDB", dialog.getEnumName()));
		EnumDataType hashEnumeration = null;
		
		if (existingDataType == null) {
			hashEnumeration = new EnumDataType(new CategoryPath("/HashDB"), hashEnumName, 4);
		} else {
			hashEnumeration = (EnumDataType) existingDataType.copy(dataTypeManager);
		}

		for (HashLocation hl: hashLocations) {
			ArrayList<HashDB.HashDBApi.HashInfo> resolved = api.resolve(algorithm, hl.getHashAsLong());
			if (resolved.size() == 0) {
				continue;
			} else if (resolved.size() > 1) {
				println(String.format("[HashDB] Hash collision for %s, using first value.", hl.getHashValue()));
			}
			HashDB.HashDBApi.HashInfo inputHashInfo = resolved.iterator().next();
			if (inputHashInfo.modules.length == 0) {
				println(String.format("[HashDB] No module found for hash %s.", hl.getHashValue()));
				continue;
			}
			if (dialog.resolveEntireModules()) {
				for (String module : inputHashInfo.modules) {
					for (HashDB.HashDBApi.HashInfo hashInfo : api.module(module, algorithm, inputHashInfo.permutation)) {
						resolveCount += onHashResolution(hashEnumeration, hl, hashInfo);
					}
				}
			} else {
				resolveCount += onHashResolution(hashEnumeration, hl, inputHashInfo);
			}
			if (tm != null) {
				tm.incrementProgress(1);
			}
		}
		tm.setMessage(String.format("updating data type %s", hashEnumeration.getDisplayName()));
		int id = currentProgram.startTransaction(String.format("updating enumeration %s", hashEnumName));
		dataTypeManager.addDataType(hashEnumeration, DataTypeConflictHandler.REPLACE_HANDLER);
		currentProgram.endTransaction(id, true);
		return String.format("added %d enum values to %s.", resolveCount, hashEnumeration.getDisplayName());
	}

	private long transformHash(long hash, String transformation) throws ScriptException {
		ScriptEngineManager manager = new ScriptEngineManager();
		manager.put("X", hash);
		ScriptEngine engine = manager.getEngineByName("js");
		return Long.valueOf(engine.eval(transformation).toString());
	}

	private void configureTableColumns(TableChooserDialog dialog) {
		StringColumnDisplay hashColumn = new StringColumnDisplay() {
			@Override
			public String getColumnName() {
				return "Hash";
			}

			@Override
			public String getColumnValue(AddressableRowObject rowObject) {
				HashLocation row = (HashLocation) rowObject;
				return row.getHashValue();
			}

			@Override
			public int compare(AddressableRowObject o1, AddressableRowObject o2) {
				return getColumnValue(o1).compareTo(getColumnValue(o2));
			}
		};

		dialog.addCustomColumn(hashColumn);
		StringColumnDisplay resolutionColumn = new StringColumnDisplay() {
			@Override
			public String getColumnName() {
				return "Resolution";
			}

			@Override
			public String getColumnValue(AddressableRowObject rowObject) {
				HashLocation row = (HashLocation) rowObject;
				return row.getResolution() == null ? "-" : row.getResolution();
			}

			@Override
			public int compare(AddressableRowObject o1, AddressableRowObject o2) {
				return getColumnValue(o1).compareTo(getColumnValue(o2));
			}
		};

		dialog.addCustomColumn(resolutionColumn);
	}

	class HashLocation implements AddressableRowObject {
		private Address address;
		private long hashValue;
		private String resolution;

		HashLocation(Address address, long hashValue) {
			this.address = address;
			this.hashValue = hashValue;
			this.resolution = null;
		}

		@Override
		public Address getAddress() {
			return address;
		}

		public long getHashAsLong() {
			return hashValue;
		}
		
		public String getHashValue() {
			return String.format("%08x", hashValue);
		}

		public String getResolution() {
			return this.resolution;
		}
	}

	private long getSelectedHash() throws Exception {
		// First try to read the value of defined or undefined data. This covers many
		// different types of locations where the cursor could be in the data view.
		Data data = currentProgram.getListing().getDataAt(currentLocation.getAddress());
		if (data != null)
			return data.getBigInteger(0, data.getDataType().getLength(), false).longValue();
		if (currentLocation instanceof DecompilerLocation) {
			Varnode varNode = ((DecompilerLocation) currentLocation).getToken().getVarnode();
			if (varNode == null || !varNode.isConstant())
				throw new Exception("You have to select a constant.");
			return varNode.getOffset();
		} else if (currentLocation instanceof OperandFieldLocation) {
			OperandFieldLocation opLoc = (OperandFieldLocation) currentLocation;
			Address opAddress = opLoc.getAddress();
			Instruction instruction = currentProgram.getListing().getInstructionAt(opAddress);
			if (instruction == null)
				throw new Exception("Operand selected, but no instruction or data found.");
			Object[] args = instruction.getOpObjects(opLoc.getOperandIndex());
			int index = opLoc.getSubOperandIndex();
			if (index < args.length && args[index] instanceof Scalar)
				return ((Scalar) args[index]).getUnsignedValue();
			throw new Exception("The selection is not a scalar value.");
		} else {
			throw new Exception(String.format("Don't know how to handle program location of type %s",
					currentLocation.getClass().getSimpleName()));
		}
	}
}
