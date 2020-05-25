package cppclassanalyzer.data.typeinfo;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import javax.help.UnsupportedOperationException;

import ghidra.app.cmd.data.rtti.ClassTypeInfo;
import ghidra.app.cmd.data.rtti.Vtable;
import ghidra.app.cmd.data.rtti.gcc.GnuUtils;
import ghidra.app.cmd.data.rtti.gcc.TypeInfoUtils;
import ghidra.app.util.SymbolPath;
import ghidra.app.util.SymbolPathParser;
import ghidra.app.util.demangler.Demangled;
import cppclassanalyzer.data.manager.ArchiveClassTypeInfoManager;
import cppclassanalyzer.data.manager.recordmanagers.ArchiveRttiRecordManager;
import cppclassanalyzer.data.vtable.ArchivedGnuVtable;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.CategoryPath;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.DataTypeManager;
import ghidra.program.model.data.FunctionDefinition;
import ghidra.program.model.data.Structure;
import ghidra.program.model.listing.GhidraClass;
import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.Namespace;
import ghidra.util.UniversalID;
import ghidra.util.exception.AssertException;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

import cppclassanalyzer.database.record.ArchivedClassTypeInfoRecord;

import static cppclassanalyzer.database.schema.fields.ArchivedClassTypeInfoSchemaFields.*;
import static ghidra.program.model.data.DataTypeConflictHandler.KEEP_HANDLER;

public final class ArchivedClassTypeInfo extends ClassTypeInfoDB {

	public static final String TABLE_NAME = "ClassTypeInfo Archive Table";

	private static final Set<String> PURE_VIRTUAL_FUNCTION_NAMES =
		Set.of("__cxa_pure_virtual", "_purecall");

	private final ArchiveRttiRecordManager manager;
	private final long address;
	private final String programName;
	private final String typeName;
	private final String symbolName;
	private final byte classId;
	private final Structure struct;
	private final Structure superStruct;
	private final ArchivedGnuVtable vtable;
	private final int[] baseOffsets;
	private final long[] baseKeys;
	private final long[] virtualKeys;
	private final Demangled demangled;

	public ArchivedClassTypeInfo(ArchiveRttiRecordManager manager, GnuClassTypeInfoDB type,
			ArchivedClassTypeInfoRecord record) {
		super(manager, record);
		this.manager = manager;
		DataTypeManager archiveDtm = (DataTypeManager) manager.getManager();
		this.address = type.getManager().encodeAddress(type.getAddress());
		record.setLongValue(ADDRESS, address);
		this.programName = type.getProgram().getName();
		this.typeName = type.getTypeName();
		this.symbolName = TypeInfoUtils.getSymbolName(type);
		this.classId = type.getClassID();
		this.struct = (Structure) archiveDtm.resolve(type.getClassDataType(), KEEP_HANDLER);
		DataTypeManager dtm = struct.getDataTypeManager();
		DataType superDt = dtm.getDataType(getCategoryPath(), "super_" + struct.getName());
		if (superDt != null) {
			this.superStruct = (Structure) archiveDtm.resolve(superDt, KEEP_HANDLER);
		} else {
			this.superStruct = this.struct;
		}
		this.baseKeys = type.getNonVirtualBaseKeys();
		this.baseOffsets = type.getOffsets();
		this.virtualKeys = type.getVirtualBaseKeys();
		record.setStringValue(PROGRAM_NAME, programName);
		record.setStringValue(TYPENAME, typeName);
		record.setStringValue(MANGLED_SYMBOL, symbolName);
		record.setByteValue(CLASS_ID, classId);
		record.setLongValue(DATATYPE_ID, struct.getUniversalID().getValue());
		record.setLongValue(SUPER_DATATYPE_ID, superStruct.getUniversalID().getValue());
		record.setLongArray(BASE_KEYS, baseKeys);
		record.setLongArray(VIRTUAL_BASE_KEYS, virtualKeys);
		record.setIntArray(BASE_OFFSETS, baseOffsets);

		// vtable must be done last to resolve symbol name
		if (Vtable.isValid(type.getVtable())) {
			// must update first or face infinite recursion
			manager.updateRecord(record);
			this.vtable = this.manager.resolve(type.getVtable());
			record.setLongValue(VTABLE_KEY, vtable.getKey());
		} else {
			this.vtable = null;
			record.setLongValue(VTABLE_KEY, -1);
		}
		manager.updateRecord(record);
		this.demangled = doDemangle(symbolName);
	}

	public ArchivedClassTypeInfo(ArchiveRttiRecordManager manager,
			ArchivedClassTypeInfoRecord record) {
		super(manager, record);
		this.manager = manager;
		DataTypeManager classManager = (DataTypeManager) manager.getManager();
		this.address = record.getLongValue(ADDRESS);
		this.programName = record.getStringValue(PROGRAM_NAME);
		this.typeName = record.getStringValue(TYPENAME);
		this.symbolName = record.getStringValue(MANGLED_SYMBOL);
		this.classId = record.getByteValue(CLASS_ID);
		UniversalID id = new UniversalID(record.getLongValue(DATATYPE_ID));
		this.struct = (Structure) classManager.findDataTypeForID(id);
		id = new UniversalID(
			record.getLongValue(SUPER_DATATYPE_ID));
		this.superStruct = (Structure) classManager.findDataTypeForID(id);
		long vtableKey = record.getLongValue(VTABLE_KEY);
		if (vtableKey != -1) {
			this.vtable = this.manager.getVtable(vtableKey);
		}
		else {
			this.vtable = null;
		}
		this.baseKeys = record.getLongArray(BASE_KEYS);
		this.baseOffsets = record.getIntArray(BASE_OFFSETS);
		this.virtualKeys = record.getLongArray(VIRTUAL_BASE_KEYS);
		this.demangled = doDemangle(symbolName);
	}

	public String getProgramName() {
		return programName;
	}

	private static Demangled doDemangle(String symbolName) {
		Demangled demangled = GnuUtils.getSpecialDemangled(symbolName);
		if (demangled == null) {
			throw new AssertException("ArchivedClassTypeInfo symbol "
				+ symbolName + " failed to demangle");
		}
		return demangled.getNamespace();
	}

	public Address getAddress(Program program) {
		return program.getAddressMap().decodeAddress(address);
	}

	@Override
	protected boolean refresh() {
		return false;
	}

	@Override
	public ArchiveClassTypeInfoManager getManager() {
		return (ArchiveClassTypeInfoManager) manager.getManager();
	}

	public byte getClassId() {
		return classId;
	}

	public String getTypeName() {
		return typeName;
	}

	public String getSymbolName() {
		return symbolName;
	}

	/**
	 * @return the datatype
	 */
	public Structure getDataType() {
		return struct;
	}

	public Structure getSuperDataType() {
		return superStruct;
	}

	public CategoryPath getCategoryPath() {
		return struct.getCategoryPath();
	}

	public ArchivedGnuVtable getArchivedVtable() {
		return vtable;
	}

	public ArchivedClassTypeInfo[] getParentModels() {
		ArchiveClassTypeInfoManager classManager = getManager();
		return Arrays.stream(baseKeys)
				.mapToObj(classManager::getClass)
				.toArray(ArchivedClassTypeInfo[]::new);
	}

	public ArchivedClassTypeInfo[] getArchivedVirtualParents() {
		ArchiveClassTypeInfoManager classManager = getManager();
		return Arrays.stream(virtualKeys)
				.mapToObj(classManager::getClass)
				.toArray(ArchivedClassTypeInfo[]::new);
	}

	/**
	 * @return the baseKeys
	 */
	protected long[] getBaseKeys() {
		return baseKeys;
	}

	protected long[] getVirtualKeys() {
		return virtualKeys;
	}

	/**
	 * @return the baseOffsets
	 */
	public int[] getBaseOffsets() {
		return baseOffsets;
	}

	@Override
	public String getName() {
		return demangled.getDemangledName();
	}

	private static UnsupportedOperationException getUnsupportedMsg(Method method) {
		return new UnsupportedOperationException(
			"Method "+method.getName()+" is not supported for an ArchivedClassTypeInfo");
	}

	@Override
	public Namespace getNamespace() {
		throw getUnsupportedMsg(new Object(){}.getClass().getEnclosingMethod());
	}

	@Override
	public String getIdentifier() {
		return AbstractClassTypeInfoDB.getIdentifier(classId);
	}

	@Override
	public Address getAddress() {
		throw getUnsupportedMsg(new Object(){}.getClass().getEnclosingMethod());
	}

	@Override
	public GhidraClass getGhidraClass() {
		throw getUnsupportedMsg(new Object(){}.getClass().getEnclosingMethod());
	}

	@Override
	public boolean hasParent() {
		return baseKeys.length > 0;
	}

	@Override
	public Set<ClassTypeInfo> getVirtualParents() {
		return Set.of(getArchivedVirtualParents());
	}

	@Override
	public boolean isAbstract() {
		if (vtable != null) {
			return Arrays.stream(vtable.getFunctionDefinitions())
				.flatMap(Arrays::stream)
				.map(FunctionDefinition::getName)
				.filter(PURE_VIRTUAL_FUNCTION_NAMES::contains)
				.findFirst()
				.isPresent();
		}
		return false;
	}

	@Override
	public Vtable findVtable(TaskMonitor monitor) throws CancelledException {
		throw getUnsupportedMsg(new Object(){}.getClass().getEnclosingMethod());
	}

	@Override
	public Vtable getVtable() {
		throw getUnsupportedMsg(new Object(){}.getClass().getEnclosingMethod());
	}

	@Override
	public Structure getClassDataType() {
		long id = manager.getTypeRecord(key).getLongValue(DATATYPE_ID);
		return (Structure) getManager().findDataTypeForID(new UniversalID(id));
	}

	@Override
	public SymbolPath getSymbolPath() {
		return new SymbolPath(SymbolPathParser.parse(demangled.getNamespaceString()));
	}

}