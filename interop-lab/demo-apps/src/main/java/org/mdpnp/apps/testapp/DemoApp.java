/*******************************************************************************
 * Copyright (c) 2014, MD PnP Program
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/
package org.mdpnp.apps.testapp;

import ice.InfusionObjectiveDataWriter;

import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import javax.imageio.ImageIO;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.UIManager;

import org.mdpnp.apps.testapp.pca.PCAPanel;
import org.mdpnp.apps.testapp.pca.VitalSign;
import org.mdpnp.apps.testapp.rrr.RapidRespiratoryRate;
import org.mdpnp.apps.testapp.sim.SimControl;
import org.mdpnp.apps.testapp.vital.VitalModel;
import org.mdpnp.apps.testapp.vital.VitalModelImpl;
import org.mdpnp.apps.testapp.xray.XRayVentPanel;
import org.mdpnp.devices.BuildInfo;
import org.mdpnp.devices.EventLoopHandler;
import org.mdpnp.devices.TimeManager;
import org.mdpnp.devices.simulation.AbstractSimulatedDevice;
import org.mdpnp.guis.swing.CompositeDevicePanel;
import org.mdpnp.rtiapi.data.DeviceDataMonitor;
import org.mdpnp.rtiapi.data.EventLoop;
import org.mdpnp.rtiapi.data.InfusionStatusInstanceModel;
import org.mdpnp.rtiapi.data.InfusionStatusInstanceModelImpl;
import org.mdpnp.rtiapi.data.QosProfiles;
import org.mdpnp.rtiapi.data.SampleArrayInstanceModel;
import org.mdpnp.rtiapi.data.SampleArrayInstanceModelImpl;
import org.mdpnp.rtiapi.data.TopicUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rti.dds.domain.DomainParticipant;
import com.rti.dds.domain.DomainParticipantFactory;
import com.rti.dds.domain.DomainParticipantFactoryQos;
import com.rti.dds.domain.DomainParticipantQos;
import com.rti.dds.infrastructure.StatusKind;
import com.rti.dds.infrastructure.StringSeq;
import com.rti.dds.publication.Publisher;
import com.rti.dds.publication.PublisherQos;
import com.rti.dds.subscription.Subscriber;
import com.rti.dds.subscription.SubscriberQos;
import com.rti.dds.topic.Topic;
import com.rti.dds.topic.TopicDescription;

//
/**
 * @author Jeff Plourde
 *
 */
public class DemoApp {

    private static String goback = null;
    private static Runnable goBackAction = null;
    protected static DemoPanel panel;
    private static String gobackBed;
    private static CardLayout ol;
    private static PartitionChooser partitionChooser;
    private static DiscoveryPeers discoveryPeers;

    private static void setGoBack(String goback, Runnable goBackAction) {
        DemoApp.goback = goback;
        DemoApp.goBackAction = goBackAction;
        DemoApp.gobackBed = panel.getBedLabel().getText();
        panel.getBack().setVisible(null != goback);
    }

    private static void goback() {
        if (null != goBackAction) {
            try {
                goBackAction.run();
            } catch (Throwable t) {
                log.error("Error in 'go back' logic", t);
            }
            goBackAction = null;
        }
        panel.getBedLabel().setText(DemoApp.gobackBed);
        ol.show(panel.getContent(), DemoApp.goback);
        panel.getBack().setVisible(false);
    }
    // final 表示这个方法不可以被子类的方法重写。final方法比非final方法要快，
    // 因为在编译的时候已经静态绑定了，不需要在运行时再动态绑定。
    // final修饰类时，表示类通常功能是完整的，它们不能被继承。
    private static final Logger log = LoggerFactory.getLogger(DemoApp.class);

    private abstract static class RunAndDone implements Runnable {
        public boolean done;
        
        public synchronized void waitForIt() {
            while(!done) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        protected synchronized void done() {
            this.done = true;
            this.notifyAll();
        }
    }
    
    //@称为注解，它与类、接口、枚举是在同一个层次，它们都称作为java的一个类型（TYPE）。
    //它可以声明在包、类、字段、方法、局部变量、方法参数等的前面，用来对这些元素进行说明，注释。
    //http://wenku.baidu.com/view/503f5ac1d5bbfd0a795673f9.html
    @SuppressWarnings("unchecked")
    public static final void start(final int domainId) throws Exception {
        UIManager.setLookAndFeel(new MDPnPLookAndFeel());

        // 管理waitset和相关的condition，waitset保存app需要的信息，没得到这些信息之前，app一直等待
        final EventLoop eventLoop = new EventLoop();
        final EventLoopHandler handler = new EventLoopHandler(eventLoop);

        // UIManager.put("List.focusSelectedCellHighlightBorder", null);
        // UIManager.put("List.focusCellHighlightBorder", null);

        // This could prove confusing
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        final String udi = AbstractSimulatedDevice.randomUDI();

        // 1. 设置participant, id是Main.java传过来的domainID
        final DomainParticipantFactoryQos qos = new DomainParticipantFactoryQos();
        DomainParticipantFactory.get_instance().get_qos(qos);
        qos.entity_factory.autoenable_created_entities = false;
        DomainParticipantFactory.get_instance().set_qos(qos);
        final DomainParticipant participant = DomainParticipantFactory.get_instance().create_participant(domainId, DomainParticipantFactory.PARTICIPANT_QOS_DEFAULT, null,
                StatusKind.STATUS_MASK_NONE);

        /**
         * This is a workaround.  Publisher.set_qos (potentially called later to
         * change partitions) expects thread priorities be set in the java range
         * Thread.MIN_PRIORITY to Thread.MAX_PRIORITY but Publisher.get_qos DOES NOT
         * populate thread priority.  So we set NORM_PRIORITY here and later
         * to avoid changing an immutable QoS. 
         */
        PublisherQos pubQos = new PublisherQos();
        participant.get_default_publisher_qos(pubQos);
        pubQos.asynchronous_publisher.asynchronous_batch_thread.priority = Thread.NORM_PRIORITY;
        pubQos.asynchronous_publisher.thread.priority = Thread.NORM_PRIORITY;

        // 2. 创建publisher和subscriber
        final Subscriber subscriber = participant.create_subscriber(DomainParticipant.SUBSCRIBER_QOS_DEFAULT, null, StatusKind.STATUS_MASK_NONE);
        final Publisher publisher = participant.create_publisher(pubQos, null, StatusKind.STATUS_MASK_NONE);
        final TimeManager timeManager = new TimeManager(publisher, subscriber, udi, "Supervisor");

        final DeviceListModel nc = new DeviceListModel(subscriber, eventLoop, timeManager);
        
        RunAndDone enable = new RunAndDone() {
            public void run() {
                nc.start();
                participant.enable();
                timeManager.start();
                qos.entity_factory.autoenable_created_entities = true;
                DomainParticipantFactory.get_instance().set_qos(qos);
                done();
            }
        };
        
        eventLoop.doLater(enable);
        
        enable.waitForIt();
        
        final DeviceListCellRenderer deviceCellRenderer = new DeviceListCellRenderer(nc);


        final ScheduledExecutorService refreshScheduler = Executors.newSingleThreadScheduledExecutor();

        final DemoFrame frame = new DemoFrame("ICE Supervisor");
        frame.setIconImage(ImageIO.read(DemoApp.class.getResource("icon.png")));
        panel = new DemoPanel();
        partitionChooser = new PartitionChooser(frame);
        partitionChooser.setSize(320, 240);
        partitionChooser.set(subscriber);
        partitionChooser.set(publisher);
        
        discoveryPeers = new DiscoveryPeers(frame);
        discoveryPeers.setSize(320, 240);
        discoveryPeers.set(participant);
        
        panel.getBedLabel().setText("OpenICE");
        panel.getVersion().setText(BuildInfo.getDescriptor());

        //用getContentPane()方法获得JFrame的内容面板，再对其加入组件。
        frame.getContentPane().add(panel);
        // 卡片式布局，多个组件共享同一个显示空间，通过CardLayout类提供的方法可以切换该空间中显示的组件
        ol = new CardLayout();
        panel.getContent().setLayout(ol);
        panel.getUdi().setText(udi);

        // supervisor的主面板，包括Available App 和 Connected Dev
        // 面板初始化的时候，appList是AppType里的设备，和deviceList为空，有设别连上了才会有内容
        final MainMenuPanel mainMenuPanel = new MainMenuPanel(AppType.getListedTypes());
        mainMenuPanel.setOpaque(false);
        panel.getContent().add(mainMenuPanel, AppType.Main.getId());
        ol.show(panel.getContent(), AppType.Main.getId());

        panel.getChangePartition().addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent e) {
                partitionChooser.refresh();
                partitionChooser.setLocationRelativeTo(DemoApp.panel);
                partitionChooser.setVisible(true);
            }
            
        });
        
//        mainMenuPanel.getDiscoveryPeers().addActionListener(new ActionListener() {
//
//            @Override
//            public void actionPerformed(ActionEvent e) {
//                discoveryPeers.setLocationRelativeTo(DemoApp.panel);
//                discoveryPeers.setVisible(true);
//            }
//            
//        });
//        
        // 点击Connected Device后弹出的窗口
        final CompositeDevicePanel devicePanel = new CompositeDevicePanel();
        panel.getContent().add(devicePanel, AppType.Device.getId());

        final VitalModel vitalModel = new VitalModelImpl(nc);
        final InfusionStatusInstanceModel pumpModel = 
                new InfusionStatusInstanceModelImpl(ice.InfusionStatusTopic.VALUE);
        ice.InfusionObjectiveDataWriter objectiveWriter = null;
        final SampleArrayInstanceModel capnoModel = 
                new SampleArrayInstanceModelImpl(ice.SampleArrayTopic.VALUE);

        // 设置要传的数据sub/pub和topic
        // VitalSign.EndTidalCO2.addToModel(vitalModel);
        if(!AppType.PCA.isDisabled() || !AppType.PCAViz.isDisabled()) {
            vitalModel.start(subscriber, publisher, eventLoop);
            TopicDescription infusionObjectiveTopic = TopicUtil.lookupOrCreateTopic(participant, ice.InfusionObjectiveTopic.VALUE,  ice.InfusionObjectiveTypeSupport.class);
            objectiveWriter = (InfusionObjectiveDataWriter) publisher.create_datawriter_with_profile((Topic) infusionObjectiveTopic, QosProfiles.ice_library,
                    QosProfiles.state, null, StatusKind.STATUS_MASK_NONE);
            pumpModel.start(subscriber, eventLoop, QosProfiles.ice_library, QosProfiles.state);
            
            // 要在面板上显示的生命指标sign都加到vitalModel里
            // VitalSign.HeartRate.addToModel(vitalModel);
            VitalSign.SpO2.addToModel(vitalModel);
            VitalSign.RespiratoryRate.addToModel(vitalModel);
            VitalSign.EndTidalCO2.addToModel(vitalModel);
        }

        if(!AppType.RRR.isDisabled()) {
            StringSeq params = new StringSeq();
            params.add("'"+rosetta.MDC_AWAY_CO2.VALUE+"'");
            params.add("'"+rosetta.MDC_IMPED_TTHOR.VALUE+"'");
            capnoModel.start(subscriber, eventLoop, "metric_id = %0 or metric_id = %1 ", params, QosProfiles.ice_library, QosProfiles.waveform_data);
        }

        //设置各功能窗口面板, panel.getcontent.add将各功能加入主panel，之后就可以点进去了
        PCAPanel _pcaPanel = null;
        if (!AppType.PCA.isDisabled()) {
            UIManager.put("TabbedPane.contentOpaque", false);
            _pcaPanel = new PCAPanel(refreshScheduler, objectiveWriter, deviceCellRenderer);
            _pcaPanel.setOpaque(false);
            panel.getContent().add(_pcaPanel, AppType.PCA.getId());
        }
        final PCAPanel pcaPanel = _pcaPanel;

        XRayVentPanel _xrayVentPanel = null;
        if (!AppType.XRay.isDisabled()) {
            _xrayVentPanel = new XRayVentPanel(panel, subscriber, eventLoop, deviceCellRenderer);
            panel.getContent().add(_xrayVentPanel, AppType.XRay.getId());
        }
        final XRayVentPanel xrayVentPanel = _xrayVentPanel;

        DataVisualization _pcaviz = null;
        if (!AppType.PCAViz.isDisabled()) {
            _pcaviz = new DataVisualization(refreshScheduler, objectiveWriter, deviceCellRenderer);
            panel.getContent().add(_pcaviz, AppType.PCAViz.getId());
        }
        final DataVisualization pcaviz = _pcaviz;

        // 继承JPanel，并实现ListDataListener接口，听取的数据一有变化，自身的Panel的显示就做相应的改动
        RapidRespiratoryRate _rrr = null;
        if (!AppType.RRR.isDisabled()) {
            _rrr = new RapidRespiratoryRate(domainId, eventLoop, subscriber, deviceCellRenderer);
            panel.getContent().add(_rrr, AppType.RRR.getId());
        }
        final RapidRespiratoryRate rrr = _rrr;

        JFrame _sim = null;
        if (!AppType.SimControl.isDisabled()) {
            SimControl simControl = new SimControl(participant);
            _sim = new JFrame("Sim Control");
            _sim.getContentPane().add(new JScrollPane(simControl));
            _sim.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
            _sim.setAlwaysOnTop(true);
            _sim.pack();
            Dimension d = new Dimension();
            _sim.getSize(d);
            d.width = 2 * d.width;
            _sim.setSize(d);

            // panel.getContent().add(_sim, AppType.SimControl.getId());
        }
        final JFrame sim = _sim;

        // 一个窗口可能有鼠标事件、窗口事件等，下面是各类事件的处理
        
        // 点击"关闭窗口"后的动作：各功能的面板数据清零，数据模型停止工作
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                refreshScheduler.shutdownNow();
                if (goBackAction != null) {
                    try {
                        goBackAction.run();
                    } catch (Throwable t) {
                        log.error("error in 'go back' handler", t);
                    }
                    goBackAction = null;
                }
                if (null != sim) {
                    // TODO things
                }
                if (pcaPanel != null) {
                    pcaPanel.setModel(null, null);
                }
                if (null != pcaviz) {
                    pcaviz.setModel(null, null);
                }
                if (null != rrr) {
                    rrr.setModel(null);
                }
                try {
                    vitalModel.stop();
                } catch(Throwable t) {
                    log.error("Error stopping the VitalModel", t);
                }
                try {
                    pumpModel.stop();
                } catch(Throwable t) {
                    log.error("Error stopping the PumpModel", t);
                }
                try {
                    capnoModel.stop();
                } catch(Throwable t) {
                    log.error("Error stopping the CapnoModel", t);
                }
                try {
                    nc.tearDown();
                } catch (Throwable t) {
                    log.error("Error tearing down the network controller", t);
                }
                
                try {
                    handler.shutdown();
                } catch (InterruptedException e1) {
                    e1.printStackTrace();
                } catch (Throwable t) {
                    log.error("Shutting down handler", t);
                }
                super.windowClosing(e);
            }
        });
        panel.getBack().setVisible(false);

        // 点击"退出应用"的动作，getback()得到的是"Exit App"按钮，ActionEvent指按鼠标或者在文本框里按回车
        panel.getBack().addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent e) {
                goback();
            }

        });

        // 点击"创建设备"的动作，getCreateAdapter()得到"Create a local ICE Device Adapter"按钮
        panel.getCreateAdapter().addActionListener(new ActionListener() {

            @SuppressWarnings({ "rawtypes" })
            @Override
            public void actionPerformed(ActionEvent e) {
                ConfigurationDialog dia = new ConfigurationDialog(null, frame);
                dia.setTitle("Create a local ICE Device Adapter");
                dia.getApplications().setModel(
                        new DefaultComboBoxModel(new Configuration.Application[] { Configuration.Application.ICE_Device_Interface }));
                dia.set(Configuration.Application.ICE_Device_Interface, Configuration.DeviceType.PO_Simulator);
                dia.remove(dia.getDomainId());
                dia.remove(dia.getDomainIdLabel());
                dia.remove(dia.getApplications());
                dia.remove(dia.getApplicationsLabel());
                dia.getWelcomeText().setRows(4);
                dia.getWelcomeText().setColumns(40);
                // dia.remove(dia.getWelcomeScroll());
                dia.getWelcomeText()
                        .setText(
                                "Typically ICE Device Adapters do not run directly within the ICE Supervisor.  This option is provided for convenient testing.  A window will be created for the device adapter.  To terminate the adapter close that window.  To exit this application you must close the supervisory window.");
                dia.getQuit().setText("Close");
                dia.pack();
                dia.setLocationRelativeTo(panel);
                final Configuration c = dia.showDialog();
                if (null != c) {
                    //编写线程运行时执行的代码有两种方式：一种是创建Thread子类的一个实例并重写run方法，第二种是创建类的时候实现Runnable接口。
                	//这里用的是第二种，新建的线程t能执行继承自Runnable的自定义的run方法。
                	//实现了Thread子类的实例可以执行多个实现了Runnable接口的线程。一个典型的应用就是线程池。线程池可以有效的管理实现了Runnable接口的线程，
                	//如果线程池满了，新的线程就会排队等候执行，直到线程池空闲出来为止。而如果线程是通过实现Thread子类实现的，这将会复杂一些。
                	//这种匿名创建的类，java会在编译时生成形如"文件名$数字"的类。
                	Thread t = new Thread(new Runnable() {
                        public void run() {
                            try {
                                DomainParticipantQos pQos = new DomainParticipantQos();
                                DomainParticipantFactory.get_instance().get_default_participant_qos(pQos);
                                pQos.discovery.initial_peers.clear();
                                for(int i = 0; i < discoveryPeers.peers.getSize(); i++) {
                                    pQos.discovery.initial_peers.add(discoveryPeers.peers.getElementAt(i));
                                    System.err.println("PEER:"+discoveryPeers.peers.getElementAt(i));
                                }
                                DomainParticipantFactory.get_instance().set_default_participant_qos(pQos);
                                SubscriberQos qos = new SubscriberQos();
                                subscriber.get_qos(qos);
                                List<String> partition = new ArrayList<String>();
                                for(int i = 0; i < qos.partition.name.size(); i++) {
                                    partition.add((String)qos.partition.name.get(i));
                                }
                                DeviceAdapter da = new DeviceAdapter();
                                da.setInitialPartition(partition.toArray(new String[0]));
                                da.start(c.getDeviceType(), domainId, c.getAddress(), true, false, eventLoop);
                                log.info("DeviceAdapter ended");
                            } catch (Exception e) {
                                log.error("Error in spawned DeviceAdapter", e);
                            }
                        }
                    });
                    t.setDaemon(true);
                    //这里常见的错误是使用t.run()方法。这样的后果是当前线程所执行run，并不是由刚创建的新线程t执行。
                    //想要让创建的新线程执行run()方法，必须调用新线程的start方法。
                    //http://ifeve.com/creating-and-starting-java-threads/
                    t.start();
                }
            }
            
        });

        // getAppList和getDeviceList分别对应supervisor界面中的available app和connected device两列，
        // 并显示在supervisor的面板上。
        // appList在mainMenuPanel初始化的时候已经有了，deviceList则要在设备创建完了才能确定
        mainMenuPanel.getDeviceList().setModel(nc);

        // 点击app后的动作，用cardlayout.show打开对应的应用卡片窗口
        mainMenuPanel.getAppList().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int idx = mainMenuPanel.getAppList().locationToIndex(e.getPoint());
                if (idx >= 0 && mainMenuPanel.getAppList().getCellBounds(idx, idx).contains(e.getPoint())) {
                    Object o = mainMenuPanel.getAppList().getModel().getElementAt(idx);
                    AppType appType = (AppType) o;

                    switch (appType) {
                    case RRR:
                        if (null != rrr) {
                        	// 设置"Exit App"按钮，以及按下按钮后的动作
                            setGoBack(AppType.Main.getId(), new Runnable() {
                                public void run() {
                                    // rrr.setModel(null, null);
                                }
                            });
                            panel.getBedLabel().setText(appType.getName());
                            // 更新数据显示
                            rrr.setModel(capnoModel);
                            //指定父窗口，和要显示的子窗口id
                            ol.show(panel.getContent(), appType.getId());
                        }
                        break;
                    case PCAViz:
                        if (null != pcaviz) {
                            setGoBack(AppType.Main.getId(), new Runnable() {
                                public void run() {
                                    pcaviz.setModel(null, null);
                                }
                            });
                            panel.getBedLabel().setText(appType.getName());
                            pcaviz.setModel(vitalModel, pumpModel);
                            ol.show(panel.getContent(), AppType.PCAViz.getId());
                        }
                        break;
                    case PCA:
                        if (null != pcaPanel) {
                            setGoBack(AppType.Main.getId(), new Runnable() {
                                public void run() {
                                    pcaPanel.setModel(null, null);
                                }
                            });
                            panel.getBedLabel().setText(appType.getName());
                            pcaPanel.setModel(vitalModel, pumpModel);
                            ol.show(panel.getContent(), AppType.PCA.getId());
                        }
                        break;
                    case XRay:
                        if (null != xrayVentPanel) {
                            setGoBack(AppType.Main.getId(), new Runnable() {
                                public void run() {
                                    xrayVentPanel.stop();
                                }
                            });
                            ol.show(panel.getContent(), AppType.XRay.getId());
                            xrayVentPanel.start(subscriber, eventLoop);
                        }
                        break;
                    case SimControl:
                        if (null != sim) {
                            sim.setLocationRelativeTo(frame);
                            sim.setVisible(true);
                            // setGoBack(AppType.Main.getId(), new Runnable() {
                            // public void run() {
                            // sim.stop();
                            // }
                            // });
                            // ol.show(panel.getContent(),
                            // AppType.SimControl.getId());
                            // sim.start();
                        }
                    case Device:
                        break;
                    case Main:
                        break;
                    default:
                        break;

                    }
                }
                super.mouseClicked(e);
            }
        });

        // 点击device后的动作，用cardlayout.show打开对应的设备监视器的卡片窗口
        mainMenuPanel.getDeviceList().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int idx = mainMenuPanel.getDeviceList().locationToIndex(e.getPoint());
                if (idx >= 0 && mainMenuPanel.getDeviceList().getCellBounds(idx, idx).contains(e.getPoint())) {
                    final Device device = (Device) mainMenuPanel.getDeviceList().getModel().getElementAt(idx);
                    // TODO threading model needs to be revisited but here this
                    // will ultimately deadlock on this AWT EventQueue thread
                    Thread t = new Thread(new Runnable() {
                        public void run() {
                            DeviceDataMonitor deviceMonitor = devicePanel.getModel();
                            if (null != deviceMonitor) {
                                deviceMonitor.stop();
                                deviceMonitor = null;
                            }
                            deviceMonitor = new DeviceDataMonitor(device.getUDI());
                            devicePanel.setModel(deviceMonitor);
                            deviceMonitor.start(subscriber, eventLoop);
                        }
                    });
                    t.setDaemon(true);
                    t.start();

                    setGoBack(AppType.Main.getId(), new Runnable() {
                        public void run() {
                            DeviceDataMonitor deviceMonitor = devicePanel.getModel();
                            if (null != deviceMonitor) {
                                deviceMonitor.stop();
                            }
                            devicePanel.setModel(null);
                        }
                    });
                    ol.show(panel.getContent(), AppType.Device.getId());
                }
                super.mouseClicked(e);
            }
        });

        
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(800, 600);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}
