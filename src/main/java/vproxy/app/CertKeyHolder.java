package vproxy.app;

import vproxy.component.exception.AlreadyExistException;
import vproxy.component.exception.NotFoundException;
import vproxy.component.ssl.CertKey;

import java.io.*;
import java.util.*;

public class CertKeyHolder {
    private Map<String, CertKey> map = new HashMap<>();

    public List<String> names() {
        return new ArrayList<>(map.keySet());
    }

    private List<String> readFile(String path) throws Exception {
        if (path.startsWith("~")) {
            path = System.getProperty("user.home") + path.substring(1);
        }
        File f = new File(path);
        if (!f.exists())
            throw new FileNotFoundException("file not found: " + path);
        if (f.isDirectory())
            throw new Exception("input path is directory: " + path);
        if (!f.canRead())
            throw new Exception("input file is not readable: " + path);
        List<String> lines = new LinkedList<>();
        try (FileReader fr = new FileReader(f); BufferedReader br = new BufferedReader(fr)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                line = line.trim();
                lines.add(line);
            }
        }
        return lines;
    }

    private List<String> getCertsFrom(String path) throws Exception {
        List<String> lines = readFile(path);
        if (lines.isEmpty()) {
            throw new Exception("file is blank or empty: " + path);
        }
        boolean begin = false;
        List<String> ret = new LinkedList<>();
        StringBuilder cert = null;
        for (String line : lines) {
            if (begin) {
                if (line.equals("-----END CERTIFICATE-----")) {
                    begin = false;
                    cert.append("\n").append(line);
                    ret.add(cert.toString());
                    cert = null;
                } else {
                    cert.append("\n").append(line);
                }
            } else {
                if (line.equals("-----BEGIN CERTIFICATE-----")) {
                    begin = true;
                    cert = new StringBuilder();
                    cert.append(line);
                }
            }
        }
        if (ret.isEmpty()) {
            throw new Exception("the file does not contain any certificate: " + path);
        }
        return ret;
    }

    private String getKeyFrom(String path) throws Exception {
        List<String> lines = readFile(path);
        if (lines.isEmpty()) {
            throw new Exception("file is blank or empty: " + path);
        }
        boolean begin = false;
        StringBuilder key = null;
        for (String line : lines) {
            if (begin) {
                if (line.equals("-----END PRIVATE KEY-----")) {
                    begin = false;
                    key.append("\n").append(line);
                } else {
                    key.append("\n").append(line);
                }
            } else {
                if (line.equals("-----BEGIN PRIVATE KEY-----")) {
                    if (key != null) {
                        throw new Exception("the file contains multiple keys: " + path);
                    }
                    begin = true;
                    key = new StringBuilder();
                    key.append(line);
                }
            }
        }
        if (key == null) {
            throw new Exception("the file does not contain any private key. note that only -----BEGIN PRIVATE KEY----- encapsulation is supported: " + path);
        }
        return key.toString();
    }

    @SuppressWarnings("DuplicateThrows")
    public void add(String alias, String[] certFilePathList, String keyFilePath) throws AlreadyExistException, Exception {
        if (map.containsKey(alias)) {
            throw new AlreadyExistException();
        }
        List<String> certs = new ArrayList<>();
        for (String certFilePath : certFilePathList) {
            certs.addAll(getCertsFrom(certFilePath));
        }
        //noinspection ToArrayCallWithZeroLengthArrayArgument
        String[] certsArray = certs.toArray(new String[certs.size()]);
        String key = getKeyFrom(keyFilePath);
        CertKey ck = new CertKey(alias, certsArray, key, certFilePathList, keyFilePath);
        ck.validate();
        map.put(alias, ck);
    }

    public CertKey get(String alias) throws NotFoundException {
        CertKey ck = map.get(alias);
        if (ck == null) {
            throw new NotFoundException();
        }
        return ck;
    }

    public void remove(String alias) throws NotFoundException {
        CertKey ck = map.remove(alias);
        if (ck == null) {
            throw new NotFoundException();
        }
    }
}
