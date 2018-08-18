package net.minecraft.world.chunk.storage;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.HashMap;
import java.util.Objects;

import com.sun.org.apache.bcel.internal.util.ByteSequence;

import gnu.trove.iterator.TIntObjectIterator;
import gnu.trove.map.hash.TIntObjectHashMap;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.chunk.NibbleArray;

//XXX
/**
 * Chunk segment, store 16x16x16
 * */
public class ExtendedBlockStorage {
	private static final String __OBFID = "CL_00000375";
	
	private static final String SKL$ID = "SKL$ID"; //short now, (skl id), notice it is sky light
	private static final String META$LIT = "META$LIT"; //byte now, (meta lit), notice it is block light
	private static final String CUSTOMTAG = "TAG";
	
	/**
	 * A storage unit of a palette
	 * */
	private final class Status {
		
		//object header
		private short address; //12bits using
		private short refcount; //at the most there can be only 4096, used to check if the status can be free
		private int hashcode; //the hashcode of data
		//object data
		private NBTTagCompound data;
		
		@Override
		public int hashCode() { return this.hashcode; }
		
		@Override
		public boolean equals(Object obj) {
			if(obj == this) return true;
			Status sta = (Status) obj;
			return sta.hashcode == this.hashcode && Objects.equals(this.data, sta.data); //这个是简化版的equals，考虑到同一个16^3内只可能有status且只有一个数据不同于其他的status
		}
	}
	
	/**
	 * A total count of the number of non-air blocks in this block storage's Chunk.
	 */
	private int blockRefCount;
	
	/**
	 * Contains the number of blocks in this block storage's parent chunk that require random ticking. Used to cull the
	 * Chunk from random tick updates for performance reasons.
	 */
	private int tickRefCount;
	
	/**
	 * Contains the bottom-most Y block represented by this ExtendedBlockStorage. Typically a multiple of 16.
	 */
	private int yBase;
	
	/**
	 * If the value if true, get skylight will return zero always
	 * */
	private boolean enableSkylight;
	
	private byte[] bAddr0; //储存状态表内的状态索引的前8位，8+4=12bit，一共能区别4096个，16x16x16=4096，刚好够用
	private NibbleArray bAddr1; //储存状态表内的状态索引的后4位
	private TIntObjectHashMap<Status> colorTab; //状态表, 最多4096个桶被用
	private HashMap<Status, Status> cacheTab; //一个缓存表，用来快速查找相同的status
	
	/**
	 * Public Constructor, args: ybase, enable sky light
	 * */
	public ExtendedBlockStorage(int ybase, boolean enableSkylight) {
		this.yBase = ybase;
		this.enableSkylight = enableSkylight;
		
		//init palette
		this.bAddr0 = new byte[4096];
		this.bAddr1 = new NibbleArray(4096, 4);
		this.colorTab = new TIntObjectHashMap();
		this.cacheTab = new HashMap<Status, Status>();
		//init data
		Status undefine = new Status();
		undefine.refcount = 4096;
		this.colorTab.put(0, undefine);
		this.cacheTab.put(undefine, undefine);
	}
	
	/**
	 * It will change the data in the given status, return the new address of the status, notice that the given data shouldn't equals the data in the given status
	 * */
	private int $change(Status accepter, NBTTagCompound data) {
		TIntObjectHashMap colorTab = this.colorTab;
		HashMap<Status, Status> cacheTab = this.cacheTab;
		
		//try free the space
		if(--accepter.refcount <= 0) {
			colorTab.remove(accepter.address);
			cacheTab.remove(accepter);
		}
		
		//create new status
		accepter = new Status();
		accepter.data = data;
		accepter.hashcode = data.hashCode();
		//search same status
		Status already = cacheTab.get(accepter);
		if(already != null) {
			already.refcount++;
			return already.address;
		}
		
		//search empty space and join
		cacheTab.put(accepter, accepter);
		short addr = accepter.address = (short) ((accepter.hashcode ^ (accepter.hashcode >>> 16)) & 0xFFF);
		accepter.refcount = 1;
		//quick check existed
		if(!colorTab.contains(addr)) {
			colorTab.put(addr, accepter);
			return addr;
		}
		
		//search empty space around i
		for(int i = 0; i < 4096; ++i) {
			//check right
			if(addr + i < 4096 && !colorTab.contains(addr + i)) {
				colorTab.put(addr += i, accepter);
				return accepter.address = addr;
			}
			
			//check left
			if(addr - i >= 0 && !colorTab.contains(addr - i)) {
				colorTab.put(addr -= i, accepter);
				return accepter.address = addr;
			}
		}
		
		//this wouldn't happen if operating properly
		cacheTab.remove(accepter);
		throw new InternalError("Impossible!");
	}
	
	/**
	 * Internal Helper, get the status address of the block, range 0~4095 for sure
	 * */
	private int $addr(int x, int y, int z) {
		return ((int) this.bAddr0[x << 8 | y << 4 | z] & 0xFF) | this.bAddr1.get(x, y, z) << 8;
	}
	
	/**
	 * Set the address of the block
	 * */
	private void $addr(int x, int y, int z, int addr) {
		this.bAddr0[x << 8 | y << 4 | z] = (byte) (addr & 0xFF);
		this.bAddr1.set(x, y, z, addr >>> 8);
	}
	
	/**
	 * Export data
	 * */
	public byte[] exportPalette() {
		try {
			ByteArrayOutputStream wrapped = new ByteArrayOutputStream(6144);
			DataOutputStream wrapper = new DataOutputStream(wrapped);
			
			//fill blocks' data
			wrapper.write(this.bAddr0);
			wrapper.write(this.bAddr1.data);
			
			//fill color tab data
			TIntObjectIterator<Status> iter = this.colorTab.iterator();
			while(iter.hasNext()) {
				iter.advance();
				Status entry = iter.value();
				
				//write object data
				if(entry.data != null) {
					wrapper.writeByte(1); //means has nbt data
					CompressedStreamTools.write(entry.data, wrapper);
				} else wrapper.writeByte(0); //means non nbt data
				
				//write object header
				wrapper.writeShort(entry.address);
				wrapper.writeShort(entry.refcount);
			}
			
			//write end sign and return result
			wrapper.writeByte(-1);
			wrapper.close();
			return wrapped.toByteArray();
		} catch(Throwable e) {
			throw new InternalError("I just wrap a byte array with a OutputStream...And write NBT data...", e);
		}
	}
	
	/**
	 * Accept data from byte array, return the read length
	 * */
	public int acceptPalette(byte[] exporter, int startPos) {
		TIntObjectHashMap<Status> colorTab = this.colorTab;
		HashMap<Status, Status> cacheTab = this.cacheTab;
		
		//try read data
		try {
			cacheTab.clear();
			colorTab.clear();
			
			//prepare reading
			ByteSequence wrapper = new ByteSequence(exporter);
			wrapper.skipBytes(startPos);
			
			//read blocks' data
			wrapper.read(this.bAddr0);
			wrapper.read(this.bAddr1.data);
			
			int opc;
			//read color tab data
			while((opc = wrapper.readByte()) != -1) {
				Status read = new Status();
				
				//read entry data&init
				if(opc == 1) {
					read.data = CompressedStreamTools.read(wrapper);
					read.hashcode = read.data.hashCode();
				}
				
				//read object header
				read.address = wrapper.readShort();
				read.refcount = wrapper.readShort();
				
				//inject data
				colorTab.put(read.address, read);
				cacheTab.put(read, read);
			}
			
			//return size
			return wrapper.getIndex() - startPos;
		} catch(Throwable e) {
			cacheTab.clear();
			colorTab.clear();
			
			Status undefine = new Status();
			undefine.refcount = 4096;
			colorTab.put(0, undefine);
			cacheTab.put(undefine, undefine);
			
			throw new RuntimeException("Invaild Byte Array!", e);
		}
	}
	
	/**
	 * Get block
	 * */
	public Block func_150819_a(int x, int y, int z) {
		NBTTagCompound nbt = ((Status) this.colorTab.get(this.$addr(x, y, z))).data;
		return Block.getBlockById((nbt == null || !nbt.hasKey(SKL$ID) ? 0 : (nbt.getShort(SKL$ID) & 0xFFF)));
	}
	
	/**
	 * Set block
	 * */
	public void func_150818_a(int x, int y, int z, Block block) {
		Status oldStatus = (Status) this.colorTab.get(this.$addr(x, y, z));
		NBTTagCompound nbt = oldStatus.data;
		int curID = Block.getIdFromBlock(block);
		int old = (nbt == null || !nbt.hasKey(SKL$ID) ? 0 : nbt.getShort(SKL$ID));
		
		//check same
		if((curID &= 0xFFF) == (old & 0xFFF)) return;
		if(nbt == null) nbt = new NBTTagCompound();
		else nbt = (NBTTagCompound) nbt.copy();
		
		//set nbt data
		nbt.setShort(SKL$ID, (short) (old & 0xF000 | curID));
		this.$addr(x, y, z, this.$change(oldStatus, nbt));
		
		//update mc's refcounter
		Block oldB = Block.getBlockById(old & 0xFFF);
		if(oldB != Blocks.air) {
			if(oldB.getTickRandomly()) --this.tickRefCount;
			--this.blockRefCount;
		}
		//update new
		if(block != Blocks.air) {
			if(block.getTickRandomly()) ++this.tickRefCount;
			++this.blockRefCount;
		}
	}
	
	/**
	 * Returns the metadata associated with the block at the given coordinates in this ExtendedBlockStorage.
	 */
    public int getExtBlockMetadata(int x, int y, int z) {
    	NBTTagCompound nbt = ((Status) this.colorTab.get(this.$addr(x, y, z))).data;
		return (nbt == null || !nbt.hasKey(META$LIT)) ? 0 : ((nbt.getByte(META$LIT) >>> 4) & 15);
    }
	
	/**
	 * Sets the metadata of the Block at the given coordinates in this ExtendedBlockStorage to the given metadata.
	 */
	public void setExtBlockMetadata(int x, int y, int z, int val) {
		Status oldStatus = (Status) this.colorTab.get(this.$addr(x, y, z));
		NBTTagCompound nbt = oldStatus.data;
		int old = (nbt == null || !nbt.hasKey(META$LIT)) ? 0 : nbt.getByte(META$LIT);
		
		//check same
		if(((old >>> 4) & 15) == (val &= 15)) return;
		if(nbt == null) nbt = new NBTTagCompound();
		else nbt = (NBTTagCompound) nbt.copy();
		
		//set nbt data
		nbt.setByte(META$LIT, (byte) (old & 15 | val << 4));
		this.$addr(x, y, z, this.$change(oldStatus, nbt));
	}
	
	/**
	 * Returns whether or not this block storage's Chunk is fully empty, based on its internal reference count.
	 */
	public boolean isEmpty() {
		return this.blockRefCount == 0;
	}
	
	/**
	 * Returns whether or not this block storage's Chunk will require random ticking, used to avoid looping through
	 * random block ticks when there are no blocks that would randomly tick.
	 */
	public boolean getNeedsRandomTick() {
		return this.tickRefCount > 0;
	}
	
	/**
	 * Returns the Y location of this ExtendedBlockStorage.
	 */
	public int getYLocation() {
		return this.yBase;
	}
	
	/**
	 * Sets the saved Sky-light value in the extended block storage structure.
	 */
	public void setExtSkylightValue(int x, int y, int z, int val) {
		Status oldStatus = (Status) this.colorTab.get(this.$addr(x, y, z));
		NBTTagCompound nbt = oldStatus.data;
		int old = (nbt == null || !nbt.hasKey(SKL$ID)) ? 0 : nbt.getShort(SKL$ID);
		
		//check same
		if(((old >>> 12) & 15) == (val &= 15)) return;
		if(nbt == null) nbt = new NBTTagCompound();
		else nbt = (NBTTagCompound) nbt.copy();
		
		//set nbt data
		nbt.setShort(SKL$ID, (short) (old & 0xFFF | val << 12));
		this.$addr(x, y, z, this.$change(oldStatus, nbt));
	}
	
	/**
	 * Gets the saved Sky-light value in the extended block storage structure.
	 */
	public int getExtSkylightValue(int x, int y, int z) {
		if(!this.enableSkylight) return 0;
		
		NBTTagCompound nbt = ((Status) this.colorTab.get(this.$addr(x, y, z))).data;
		return (nbt == null || !nbt.hasKey(SKL$ID)) ? 0 : ((nbt.getShort(SKL$ID) >>> 12) & 15);
	}
	
	/**
	 * Sets the saved Block-light value in the extended block storage structure.
	 */
	public void setExtBlocklightValue(int x, int y, int z, int val) {
		Status oldStatus = (Status) this.colorTab.get(this.$addr(x, y, z));
		NBTTagCompound nbt = oldStatus.data;
		int old = (nbt == null || !nbt.hasKey(META$LIT)) ? 0 : nbt.getByte(META$LIT);
		
		//check same
		if((old & 15) == (val &= 15)) return;
		if(nbt == null) nbt = new NBTTagCompound();
		else nbt = (NBTTagCompound) nbt.copy();
		
		//set nbt data
		nbt.setByte(META$LIT, (byte) (old & 240 | val));
		this.$addr(x, y, z, this.$change(oldStatus, nbt));
	}
	
	/**
	 * Gets the saved Block-light value in the extended block storage structure.
	 */
	public int getExtBlocklightValue(int x, int y, int z) {
		NBTTagCompound nbt = ((Status) this.colorTab.get(this.$addr(x, y, z))).data;
		return (nbt == null || !nbt.hasKey(META$LIT)) ? 0 : (nbt.getByte(META$LIT) & 15);
	}
	
	/**
	 * Change the peeker's data into the custom data of the block(but you can't change the data in it, to change it, invoke {@link NBTTagCompound#copy() copy}), the peeker's data is valid until next 'set' operation call
	 * passing null equals 'hasTag', notice passing a already-peeker is allowed, return true for tag existed, false for otherwise
	 * */
	public boolean peekTag(int x, int y, int z, NBTTagCompound peeker) {
		NBTTagCompound nbt = ((Status) this.colorTab.get(this.$addr(x, y, z))).data;
		if(nbt == null || !nbt.hasKey(CUSTOMTAG)) return false;
		if(peeker == null) return true;
		
		//peek data
		peeker.peek(nbt.getCompoundTag(CUSTOMTAG));
		return true;
	}
	
	/**
	 * Get the custom data of the block(the copy of), if the data is non-existed, it will return null
	 * */
	public NBTTagCompound getTag(int x, int y, int z) {
		NBTTagCompound nbt = ((Status) this.colorTab.get(this.$addr(x, y, z))).data;
		return (nbt == null || !nbt.hasKey(CUSTOMTAG)) ? null : nbt.getCompoundTag(CUSTOMTAG).copy();
	}
	
	/**
	 * Set the custom data of the block, passing null means delete it, return itself
	 * */
	public ExtendedBlockStorage setTag(int x, int y, int z, NBTTagCompound tag) {
		Status oldStatus = (Status) this.colorTab.get(this.$addr(x, y, z));
		NBTTagCompound nbt = oldStatus.data;
		if(tag != null) tag = (NBTTagCompound) tag.copy();
		
		//check same
		if(Objects.equals((nbt == null || !nbt.hasKey(CUSTOMTAG)) ? null : nbt.getCompoundTag(CUSTOMTAG), tag)) return this;
		if(nbt == null) nbt = new NBTTagCompound();
		else nbt = nbt.copy();
		
		//set nbt data
		if(tag != null) nbt.setTag(CUSTOMTAG, tag);
		else nbt.removeTag(CUSTOMTAG);
		this.$addr(x, y, z, this.$change(oldStatus, nbt));
		return this; //return itself for other operations
	}
	
	/**
	 * Count internal
	 * */
	public void removeInvalidBlocks() {
		this.blockRefCount = 0;
		this.tickRefCount = 0;
		
		for(int var1 = 0; var1 < 16; ++var1) {
			for(int var2 = 0; var2 < 16; ++var2) {
				for(int var3 = 0; var3 < 16; ++var3) {
					Block var4 = this.func_150819_a(var1, var2, var3);
					
					if(var4 != Blocks.air) {
						++this.blockRefCount;
						
						if(var4.getTickRandomly()) ++this.tickRefCount;
					}
				}
			}
		}
	}
}
