package io.mycat.mycat2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

import io.mycat.mycat2.beans.MySQLCharset;
import io.mycat.mycat2.beans.MySQLPackageInf;
import io.mycat.mycat2.beans.SchemaBean;
import io.mycat.mysql.Capabilities;
import io.mycat.mysql.Isolation;
import io.mycat.mysql.packet.HandshakePacket;
import io.mycat.mysql.packet.MySQLPacket;
import io.mycat.proxy.BufferOptState;
import io.mycat.proxy.BufferPool;
import io.mycat.proxy.ProxyBuffer;
import io.mycat.proxy.ProxyRuntime;
import io.mycat.proxy.UserProxySession;
import io.mycat.util.ParseUtil;
import io.mycat.util.RandomUtil;
import io.mycat.util.StringUtil;

public class MySQLSession extends UserProxySession {

	public SQLCommand curSQLCommand;

	/**
	 * 当前处理中的SQL报文的信息（前端）
	 */
	public MySQLPackageInf curFrontMSQLPackgInf = new MySQLPackageInf();
	/**
	 * 当前处理中的SQL报文的信息（后端）
	 */
	public MySQLPackageInf curBackendMSQLPackgInf = new MySQLPackageInf();

	/**
	 * 前端字符集
	 */
	public MySQLCharset frontCharSet;
	/**
	 * 前端用户
	 */
	public String clientUser;
	/**
	 * Mycat Schema
	 */
	public SchemaBean schema;

	/**
	 * 事务隔离级别
	 */
	public Isolation isolation = Isolation.REPEATED_READ;

	/**
	 * 认证中的seed报文数据
	 */
	public byte[] seed;

	protected int getServerCapabilities() {
		int flag = 0;
		flag |= Capabilities.CLIENT_LONG_PASSWORD;
		flag |= Capabilities.CLIENT_FOUND_ROWS;
		flag |= Capabilities.CLIENT_LONG_FLAG;
		flag |= Capabilities.CLIENT_CONNECT_WITH_DB;
		// flag |= Capabilities.CLIENT_NO_SCHEMA;
		// boolean usingCompress = MycatServer.getInstance().getConfig()
		// .getSystem().getUseCompression() == 1;
		// if (usingCompress) {
		// flag |= Capabilities.CLIENT_COMPRESS;
		// }
		flag |= Capabilities.CLIENT_ODBC;
		flag |= Capabilities.CLIENT_LOCAL_FILES;
		flag |= Capabilities.CLIENT_IGNORE_SPACE;
		flag |= Capabilities.CLIENT_PROTOCOL_41;
		flag |= Capabilities.CLIENT_INTERACTIVE;
		// flag |= Capabilities.CLIENT_SSL;
		flag |= Capabilities.CLIENT_IGNORE_SIGPIPE;
		flag |= Capabilities.CLIENT_TRANSACTIONS;
		// flag |= ServerDefs.CLIENT_RESERVED;
		flag |= Capabilities.CLIENT_SECURE_CONNECTION;
		return flag;
	}

	/**
	 * 回应客户端（front或Sever）OK 报文。
	 * 
	 * @param pkg
	 *            ，必须要是OK报文或者Err报文
	 * @throws IOException
	 */
	public void responseOKOrError(MySQLPacket pkg, boolean front) throws IOException {
		if (front) {
			frontBuffer.changeOwner(true);
			pkg.write(this.frontBuffer);
			frontBuffer.flip();
			this.writeToChannel(frontBuffer, this.frontChannel);
		} else {
			frontBuffer.changeOwner(false);
			pkg.write(this.frontBuffer);
			frontBuffer.flip();
			this.writeToChannel(frontBuffer, this.backendChannel);
		}
	}

	/**
	 * 给客户端（front）发送认证报文
	 * 
	 * @throws IOException
	 */
	public void sendAuthPackge() throws IOException {
		// 生成认证数据
		byte[] rand1 = RandomUtil.randomBytes(8);
		byte[] rand2 = RandomUtil.randomBytes(12);

		// 保存认证数据
		byte[] seed = new byte[rand1.length + rand2.length];
		System.arraycopy(rand1, 0, seed, 0, rand1.length);
		System.arraycopy(rand2, 0, seed, rand1.length, rand2.length);
		this.seed = seed;

		// 发送握手数据包
		HandshakePacket hs = new HandshakePacket();
		hs.packetId = 0;
		hs.protocolVersion = Version.PROTOCOL_VERSION;
		hs.serverVersion = Version.SERVER_VERSION;
		hs.threadId = this.getSessionId();
		hs.seed = rand1;
		hs.serverCapabilities = getServerCapabilities();
		// hs.serverCharsetIndex = (byte) (charsetIndex & 0xff);
		hs.serverStatus = 2;
		hs.restOfScrambleBuff = rand2;
		hs.write(this.frontBuffer);
		//进行读取状态的切换,即将写状态切换 为读取状态
		frontBuffer.flip();
		this.writeToChannel(frontBuffer, this.frontChannel);
	}

	public MySQLSession(BufferPool bufPool, Selector nioSelector, SocketChannel frontChannel) throws IOException {
		super(bufPool, nioSelector, frontChannel);

	}

	/**
	 * 向前端发送数据报文,需要先确定为Write状态并确保写入位置的正确（frontBuffer.writeState)
	 * 
	 * @param rawPkg
	 * @throws IOException
	 */
	public void answerFront(byte[] rawPkg) throws IOException {
		frontBuffer.writeBytes(rawPkg);
		frontBuffer.flip();
		writeToChannel(frontBuffer, frontChannel);
	}



	/**
	 * 解析MySQL报文，解析的结果存储在curMSQLPackgInf中，如果解析到完整的报文，就返回TRUE
	 * 如果解析的过程中同时要移动ProxyBuffer的readState位置，即标记为读过，后继调用开始解析下一个报文，则需要参数markReaded=true
	 * 
	 * @param proxyBuf
	 * @return
	 * @throws IOException
	 */
	public boolean resolveMySQLPackage(ProxyBuffer proxyBuf, MySQLPackageInf curPackInf, boolean markReaded)
			throws IOException {
		boolean readWholePkg = false;
		ByteBuffer buffer = proxyBuf.getBuffer();
		BufferOptState readState = proxyBuf.readState;
		//读取的偏移位置
		int offset = readState.optPostion;
		//读取的总长度
		int limit = readState.optLimit;
		//读取当前的总长度 
		int totalLen = limit - offset;
		if (totalLen == 0) {
			return false;
		}
		
		//如果当前跨多个报文
		if (curPackInf.crossBuffer) {
			if (curPackInf.remainsBytes <= totalLen) {
				// 剩余报文结束
				curPackInf.endPos = offset + curPackInf.remainsBytes;
				curPackInf.remainsBytes = 0;
				readWholePkg = true;
			} else {// 剩余报文还没读完，等待下一次读取
				curPackInf.remainsBytes -= totalLen;
				curPackInf.endPos = limit;
				readWholePkg = false;
			}
		}
		//验证当前指针位置是否
		else if (!ParseUtil.validateHeader(offset, limit)) {
			logger.debug("not read a whole packet ,session {},offset {} ,limit {}", getSessionId(), offset, limit);
			readWholePkg = false;
		}
		
		//解包获取包的数据长度
		int pkgLength = ParseUtil.getPacketLength(buffer, offset);
		// 解析报文类型
		final byte packetType = buffer.get(offset + ParseUtil.msyql_packetHeaderSize);
		//包的类型
		curPackInf.pkgType = packetType;
		//设置包的长度
		curPackInf.pkgLength = pkgLength;
		//设置偏移位置
		curPackInf.startPos = offset;
		//设置跨buffer为false
		curPackInf.crossBuffer = false;
		curPackInf.remainsBytes = 0;
		//如果当前需要跨buffer处理
		if ((offset + pkgLength) > limit) {
			logger.debug(
					"Not a whole packet: required length = {} bytes, cur total length = {} bytes, "
							+ "ready to handle the next read event",
					getSessionId(), buffer.hashCode(), pkgLength, limit);
			curPackInf.crossBuffer = true;
			curPackInf.remainsBytes = offset + pkgLength - limit;
			curPackInf.endPos = limit;
			readWholePkg = false;
		} else {
			// 读到完整报文
			curPackInf.endPos = curPackInf.pkgLength + curPackInf.startPos;
			if (ProxyRuntime.INSTANCE.isTraceProtocol()) {
				/**
				 * @todo 跨多个报文的情况下，修正错误。
				 */
				final String hexs = StringUtil.dumpAsHex(buffer, curPackInf.startPos, curPackInf.pkgLength);
				logger.info(
						"     session {} packet: startPos={}, offset = {}, length = {}, type = {}, cur total length = {},pkg HEX\r\n {}",
						getSessionId(), curPackInf.startPos, offset, pkgLength, packetType, limit, hexs);
			}
			readWholePkg = true;
		}
		if (markReaded) {
			readState.optPostion = curPackInf.endPos;
		}
		return readWholePkg;

	}

	public void close(String message) {
		if (!this.isClosed()) {
			super.close(message);
			this.curSQLCommand.clearResouces(true);
		}
	}

}
