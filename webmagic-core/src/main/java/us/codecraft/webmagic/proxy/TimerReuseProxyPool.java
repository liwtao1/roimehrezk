package us.codecraft.webmagic.proxy;

import org.apache.http.HttpHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.codecraft.webmagic.utils.FilePersistentBase;
import us.codecraft.webmagic.utils.ProxyUtils;

import java.io.*;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;

/**
 * Pooled Proxy Object
 *
 * @author yxssfxwzy@sina.com <br>
 * @see Proxy
 * @since 0.5.1
 */
public class TimerReuseProxyPool implements ProxyPool {

    private Logger logger = LoggerFactory.getLogger(getClass());

    private BlockingQueue<TimerReuseProxy> proxyQueue = new DelayQueue<TimerReuseProxy>();
    private Map<String, TimerReuseProxy> allProxy = new ConcurrentHashMap<String, TimerReuseProxy>();

    private int reuseInterval = 1500;// ms
    private int reviveTime = 2 * 60 * 60 * 1000;// ms
    private int saveProxyInterval = 10 * 60 * 1000;// ms

    private boolean isEnable = false;
    private boolean validateWhenInit = false;
    // private boolean isUseLastProxy = true;
    private String proxyFilePath = "/data/webmagic/lastUse.proxy";

    private FilePersistentBase fBase = new FilePersistentBase();

    private Timer timer = new Timer(true);
    private TimerTask saveProxyTask = new TimerTask() {

        @Override
        public void run() {
            saveProxyList();
            logger.info(allProxyStatus());
        }
    };

    public TimerReuseProxyPool() {
        this(null, true);
    }

    public TimerReuseProxyPool(List<String[]> httpProxyList) {
        this(httpProxyList, true);
    }

    public TimerReuseProxyPool(List<String[]> httpProxyList, boolean isUseLastProxy) {
        if (httpProxyList != null) {
            addProxy(httpProxyList.toArray(new String[httpProxyList.size()][]));
        }
        if (isUseLastProxy) {
            if (!new File(proxyFilePath).exists()) {
                setFilePath();
            }
            readProxyList();
            timer.schedule(saveProxyTask, 0, saveProxyInterval);
        }
    }

    private void setFilePath() {
        String tmpDir = System.getProperty("java.io.tmpdir");
        String path = tmpDir + FilePersistentBase.PATH_SEPERATOR + "webmagic" + FilePersistentBase.PATH_SEPERATOR + "lastUse.proxy";
        if (tmpDir != null && new File(tmpDir).isDirectory()) {
            fBase.setPath(tmpDir + FilePersistentBase.PATH_SEPERATOR + "webmagic");
            File f = fBase.getFile(path);
            if (!f.exists()) {
                try {
                    f.createNewFile();

                } catch (IOException e) {
                    logger.error("proxy file create error", e);
                }
            }

        } else {
            logger.error("java tmp dir not exists");
        }
        this.proxyFilePath = path;
    }

    private void saveProxyList() {
        if (allProxy.size() == 0) {
            return;
        }
        try {
            ObjectOutputStream os = new ObjectOutputStream(new FileOutputStream(fBase.getFile(proxyFilePath)));
            os.writeObject(prepareForSaving());
            os.close();
            logger.info("save proxy");
        } catch (FileNotFoundException e) {
            logger.error("proxy file not found", e);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Map<String, Proxy> prepareForSaving() {
        Map<String, TimerReuseProxy> tmp = new HashMap<String, TimerReuseProxy>();
        for (Entry<String, TimerReuseProxy> e : allProxy.entrySet()) {
            TimerReuseProxy p = e.getValue();
            p.setFailedNum(0);
            tmp.put(e.getKey(), p);
        }
        return tmp;
    }

    private void readProxyList() {
        try {
            ObjectInputStream is = new ObjectInputStream(new FileInputStream(fBase.getFile(proxyFilePath)));
            addProxy((Map<String, Proxy>) is.readObject());
            is.close();
        } catch (FileNotFoundException e) {
            logger.info("last use proxy file not found", e);
        } catch (IOException e) {
            // e.printStackTrace();
        } catch (ClassNotFoundException e) {
            // e.printStackTrace();
        }
    }

    private void addProxy(Map<String, Proxy> httpProxyMap) {
        isEnable = true;
        for (Entry<String, Proxy> entry : httpProxyMap.entrySet()) {
            try {
                if (allProxy.containsKey(entry.getKey())) {
                    continue;
                }
                if (!validateWhenInit || ProxyUtils.validateProxy(entry.getValue().getHttpHost())) {
                    entry.getValue().setFailedNum(0);
                    entry.getValue().setReuseTimeInterval(reuseInterval);
                    proxyQueue.add(entry.getValue());
                    allProxy.put(entry.getKey(), entry.getValue());
                }
            } catch (NumberFormatException e) {
                logger.error("HttpHost init error:", e);
            }
        }
        logger.info("proxy pool size>>>>" + allProxy.size());
    }

    public void addProxy(Proxy... httpProxyList) {
        isEnable = true;
        for (Proxy proxy : httpProxyList) {
            if (!validateWhenInit || ProxyUtils.validateProxy(proxy.getProxyHost())) {
                TimerReuseProxy p = new TimerReuseProxy(proxy.getProxyHost(), proxy.getUsername(), proxy.getPassword(), reuseInterval);
                proxyQueue.add(p);
                allProxy.put(p.getProxyHost().getHost(), p);
            }
        }
        logger.info("proxy pool size>>>>" + allProxy.size());
    }

    public TimerReuseProxy getProxy() {
        TimerReuseProxy proxy = null;
        try {
            Long time = System.currentTimeMillis();
            proxy = proxyQueue.take();
            double costTime = (System.currentTimeMillis() - time) / 1000.0;
            if (costTime > reuseInterval) {
                logger.info("get proxy time >>>> " + costTime);
            }
            TimerReuseProxy p = allProxy.get(proxy.getProxyHost().getHost());
            p.setLastBorrowTime(System.currentTimeMillis());
            p.borrowNumIncrement(1);
        } catch (InterruptedException e) {
            logger.error("get proxy error", e);
        }
        if (proxy == null) {
            throw new NoSuchElementException();
        }
        return proxy;
    }

    public void returnProxy(Proxy proxy, int statusCode) {
        TimerReuseProxy p = allProxy.get(proxy.getProxyHost());
        if (p == null) {
            return;
        }
        switch (statusCode) {
            case TimerReuseProxy.SUCCESS:
                p.setReuseTimeInterval(reuseInterval);
                p.setFailedNum(0);
                p.setFailedErrorType(new ArrayList<Integer>());
                p.recordResponse();
                p.successNumIncrement(1);
                break;
            case TimerReuseProxy.ERROR_403:
                // banned,try longer interval
                p.fail(TimerReuseProxy.ERROR_403);
                p.setReuseTimeInterval(reuseInterval * p.getFailedNum());
                logger.info(proxy + " >>>> reuseTimeInterval is >>>> " + p.getReuseTimeInterval() / 1000.0);
                break;
            case TimerReuseProxy.ERROR_BANNED:
                p.fail(TimerReuseProxy.ERROR_BANNED);
                p.setReuseTimeInterval(10 * 60 * 1000 * p.getFailedNum());
                logger.warn("this proxy is banned >>>> " + p.getHttpHost());
                logger.info(proxy + " >>>> reuseTimeInterval is >>>> " + p.getReuseTimeInterval() / 1000.0);
                break;
            case TimerReuseProxy.ERROR_404:
                // p.fail(Proxy.ERROR_404);
                // p.setReuseTimeInterval(reuseInterval * p.getFailedNum());
                break;
            default:
                p.fail(statusCode);
                break;
        }
        if (p.getFailedNum() > 20) {
            p.setReuseTimeInterval(reviveTime);
            logger.error("remove proxy >>>> " + proxy + ">>>>" + p.getFailedType() + " >>>> remain proxy >>>> " + proxyQueue.size());
            return;
        }
        if (p.getFailedNum() > 0 && p.getFailedNum() % 5 == 0) {
            if (!ProxyUtils.validateProxy(proxy)) {
                p.setReuseTimeInterval(reviveTime);
                logger.error("remove proxy >>>> " + proxy + ">>>>" + p.getFailedType() + " >>>> remain proxy >>>> " + proxyQueue.size());
                return;
            }
        }
        try {
            proxyQueue.put(p);
        } catch (InterruptedException e) {
            logger.warn("proxyQueue return proxy error", e);
        }
    }

    public String allProxyStatus() {
        String re = "all proxy info >>>> \n";
        for (Entry<String, Proxy> entry : allProxy.entrySet()) {
            re += entry.getValue().toString() + "\n";
        }
        return re;
    }

    public int getIdleNum() {
        return proxyQueue.size();
    }

    public int getReuseInterval() {
        return reuseInterval;
    }

    public void setReuseInterval(int reuseInterval) {
        this.reuseInterval = reuseInterval;
    }

    public void enable(boolean isEnable) {
        this.isEnable = isEnable;
    }

    public boolean isEnable() {
        return isEnable;
    }

    public int getReviveTime() {
        return reviveTime;
    }

    public void setReviveTime(int reviveTime) {
        this.reviveTime = reviveTime;
    }

    public boolean isValidateWhenInit() {
        return validateWhenInit;
    }

    public void validateWhenInit(boolean validateWhenInit) {
        this.validateWhenInit = validateWhenInit;
    }

    public int getSaveProxyInterval() {
        return saveProxyInterval;
    }

    public void setSaveProxyInterval(int saveProxyInterval) {
        this.saveProxyInterval = saveProxyInterval;
    }

    public String getProxyFilePath() {
        return proxyFilePath;
    }

    public void setProxyFilePath(String proxyFilePath) {
        this.proxyFilePath = proxyFilePath;
    }

}
