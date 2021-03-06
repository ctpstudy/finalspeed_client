// Copyright (c) 2015 D1SM.net

package net.fs.rudp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Vector;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

//import net.fs.cap.CapEnv;
import net.fs.cap.VDatagramSocket;
import net.fs.rudp.message.MessageType;
import net.fs.utils.ByteIntConvert;
import net.fs.utils.MLog;
import net.fs.utils.MessageCheck;

public class Route {

	private DatagramSocket ds;
	public HashMap<Integer, ConnectionUDP> connTable;
	Route route;
	Thread mainThread;
	Thread reveiveThread;

	public static ThreadPoolExecutor es;

	public AckListManage delayAckManage;

	Object syn_ds2Table = new Object();

	Object syn_tunTable = new Object();

	Random ran = new Random();

	public int localclientId = Math.abs(ran.nextInt());

	LinkedBlockingQueue<DatagramPacket> packetBuffer = new LinkedBlockingQueue<DatagramPacket>();

	String pocessName = "";

	HashSet<Integer> setedTable = new HashSet<Integer>();

	static int vv;

	HashSet<Integer> closedTable = new HashSet<Integer>();

	public static int localDownloadSpeed, localUploadSpeed;

	ClientManager clientManager;

	HashSet<Integer> pingTable = new HashSet<Integer>();

	// public CapEnv capEnv=null;

	public ClientControl lastClientControl;

	// public boolean useTcpTun=true;

	public HashMap<Object, Object> contentTable = new HashMap<Object, Object>();

	private static List<Trafficlistener> listenerList = new Vector<Trafficlistener>();

	{

		delayAckManage = new AckListManage();
	}

	static {
		SynchronousQueue queue = new SynchronousQueue();
		ThreadPoolExecutor executor = new ThreadPoolExecutor(100, Integer.MAX_VALUE, 10 * 1000, TimeUnit.MILLISECONDS,
				queue);
		es = executor;
	}

	// public Route(String pocessName,short routePort,int mode2,boolean
	// tcp,boolean tcpEnvSuccess) throws Exception{
	public Route(String pocessName, short routePort) throws Exception {
		this.pocessName = pocessName;
		ds = new DatagramSocket();

		connTable = new HashMap<Integer, ConnectionUDP>();
		clientManager = new ClientManager(this);
		reveiveThread = new Thread() {
			@Override
			public void run() {
				while (true) {
					byte[] b = new byte[1500];
					DatagramPacket dp = new DatagramPacket(b, b.length);
					try {
						ds.receive(dp);
						// MLog.println("接收 "+dp.getAddress());
						packetBuffer.add(dp);
					} catch (IOException e) {
						e.printStackTrace();
						try {
							Thread.sleep(1);
						} catch (InterruptedException e1) {
							e1.printStackTrace();
						}
						continue;
					}
				}
			}
		};
		reveiveThread.start();

		mainThread = new Thread() {
			public void run() {
				while (true) {
					DatagramPacket dp = null;
					try {
						dp = packetBuffer.take();
					} catch (InterruptedException e1) {
						e1.printStackTrace();
					}
					if (dp == null) {
						continue;
					}
					long t1 = System.currentTimeMillis();
					byte[] dpData = dp.getData();

					int sType = 0;
					if (dp.getData().length < 4) {
						return;
					}
					sType = MessageCheck.checkSType(dp);
					// MLog.println("route receive MessageType111#"+sType+"
					// "+dp.getAddress()+":"+dp.getPort());
					if (dp != null) {

						final int connectId = ByteIntConvert.toInt(dpData, 4);
						int remote_clientId = ByteIntConvert.toInt(dpData, 8);

						if (closedTable.contains(connectId) && connectId != 0) {
							// #MLog.println("忽略已关闭连接包 "+connectId);
							continue;
						}

						if (sType == net.fs.rudp.message.MessageType.sType_PingMessage
								|| sType == net.fs.rudp.message.MessageType.sType_PingMessage2) {
							ClientControl clientControl = null;
							String key = dp.getAddress().getHostAddress() + ":" + dp.getPort();
							int sim_clientId = Math.abs(key.hashCode());
							clientControl = clientManager.getClientControl(sim_clientId, dp.getAddress(), dp.getPort());
							clientControl.onReceivePacket(dp);
						} else {
							if (!setedTable.contains(remote_clientId)) {
								String key = dp.getAddress().getHostAddress() + ":" + dp.getPort();
								int sim_clientId = Math.abs(key.hashCode());
								ClientControl clientControl = clientManager.getClientControl(sim_clientId,
										dp.getAddress(), dp.getPort());
								if (clientControl.getClientId_real() == -1) {
									clientControl.setClientId_real(remote_clientId);
									// #MLog.println("首次设置clientId
									// "+remote_clientId);
								} else {
									if (clientControl.getClientId_real() != remote_clientId) {
										// #MLog.println("服务端重启更新clientId
										// "+sType+"
										// "+clientControl.getClientId_real()+"
										// new: "+remote_clientId);
										clientControl.updateClientId(remote_clientId);
									}
								}
								// #MLog.println("cccccc "+sType+"
								// "+remote_clientId);
								setedTable.add(remote_clientId);
							}

							final ConnectionUDP ds3 = connTable.get(connectId);
							if (ds3 != null) {
								final DatagramPacket dp2 = dp;
								ds3.receiver.onReceivePacket(dp2);
								if (sType == MessageType.sType_DataMessage) {
									TrafficEvent event = new TrafficEvent("", ran.nextLong(), dp.getLength(),
											TrafficEvent.type_downloadTraffic);
									fireEvent(event);
								}
							}

						}
					}
				}
			}
		};
		mainThread.start();

	}

	public static void addTrafficlistener(Trafficlistener listener) {
		listenerList.add(listener);
	}

	static void fireEvent(TrafficEvent event) {
		for (Trafficlistener listener : listenerList) {
			int type = event.getType();
			if (type == TrafficEvent.type_downloadTraffic) {
				listener.trafficDownload(event);
			} else if (type == TrafficEvent.type_uploadTraffic) {
				listener.trafficUpload(event);
			}
		}
	}

	public void sendPacket(DatagramPacket dp) throws IOException {
		ds.send(dp);
	}

	public ConnectionProcessor createTunnelProcessor() {
		ConnectionProcessor o = null;
		try {
			Class onwClass = Class.forName(pocessName);
			o = (ConnectionProcessor) onwClass.newInstance();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		} catch (InstantiationException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		}
		return o;
	}

	void removeConnection(ConnectionUDP conn) {
		synchronized (syn_ds2Table) {
			closedTable.add(conn.connectId);
			connTable.remove(conn.connectId);
		}
	}

	// 发起连接
	public ConnectionUDP getConnection(String address, int dstPort, String password) throws Exception {
		InetAddress dstIp = InetAddress.getByName(address);
		int connectId = Math.abs(ran.nextInt());
		String key = dstIp.getHostAddress() + ":" + dstPort;
		int remote_clientId = Math.abs(key.hashCode());
		ClientControl clientControl = clientManager.getClientControl(remote_clientId, dstIp, dstPort);
		clientControl.setPassword(password);
		ConnectionUDP conn = new ConnectionUDP(this, dstIp, dstPort, connectId, clientControl);
		synchronized (syn_ds2Table) {
			connTable.put(connectId, conn);
		}
		clientControl.addConnection(conn);
		lastClientControl = clientControl;
		return conn;
	}

	// public boolean isUseTcpTun() {
	// return useTcpTun;
	// }

	// public void setUseTcpTun(boolean useTcpTun) {
	// this.useTcpTun = useTcpTun;
	// }

}
