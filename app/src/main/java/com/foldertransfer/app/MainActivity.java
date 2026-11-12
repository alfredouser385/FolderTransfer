package com.foldertransfer.app;

import android.app.*;
import android.os.*;
import android.content.*;
import android.net.wifi.WifiManager;
import android.view.*;
import android.widget.*;
import android.text.InputType;

import androidx.documentfile.provider.DocumentFile;

import java.io.*;
import java.net.*;
import java.util.*;

public class MainActivity extends Activity {
    private static final int PICK_SEND = 1;
    private static final int PICK_DEST = 2;
    private static final int PORT = 8988;

    private DocumentFile sendFolder, destination;
    private EditText ipInput;
    private TextView status;
    private Button sendButton, receiveButton;
    private volatile boolean receiving = false;

    private void ui(Runnable r) {
        runOnUiThread(r);
    }

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 28, 24, 24);
        root.setBackgroundColor(0xFFFFFFFF);

        TextView title = new TextView(this);
        title.setText("FolderTransfer");
        title.setTextSize(28);
        title.setTextColor(0xFF202124);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Kirim folder antar HP tanpa ZIP");
        subtitle.setTextSize(15);
        subtitle.setTextColor(0xFF666666);
        subtitle.setPadding(0, 6, 0, 24);
        root.addView(subtitle);

        Button choose = new Button(this);
        choose.setText("PILIH FOLDER");
        root.addView(choose);
        choose.setOnClickListener(v -> pickFolder(PICK_SEND));

        ipInput = new EditText(this);
        ipInput.setSingleLine(true);
        ipInput.setHint("IP penerima, contoh 192.168.1.5");
        ipInput.setInputType(InputType.TYPE_CLASS_TEXT);
        root.addView(ipInput);

        sendButton = new Button(this);
        sendButton.setText("KIRIM FOLDER");
        root.addView(sendButton);
        sendButton.setOnClickListener(v -> sendFolder());

        receiveButton = new Button(this);
        receiveButton.setText("TERIMA FOLDER");
        root.addView(receiveButton);
        receiveButton.setOnClickListener(v -> pickFolder(PICK_DEST));

        status = new TextView(this);
        status.setText("Pilih folder untuk memulai.");
        status.setTextSize(14);
        status.setTextColor(0xFF333333);
        status.setPadding(0, 24, 0, 12);
        root.addView(status);

        setContentView(root);
    }

    private void pickFolder(int request) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, request);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) return;

        getContentResolver().takePersistableUriPermission(
                data.getData(),
                data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        );

        DocumentFile folder = DocumentFile.fromTreeUri(this, data.getData());

        if (request == PICK_SEND) {
            sendFolder = folder;
            status.setText("Folder kirim: " + folder.getName());
        } else if (request == PICK_DEST) {
            destination = folder;
            status.setText("Folder tujuan: " + folder.getName()
                    + "\nMenyiapkan penerima di port " + PORT);
            startReceiver();
        }
    }

    private void sendFolder() {
        if (sendFolder == null) {
            status.setText("Pilih folder terlebih dahulu.");
            return;
        }

        String host = ipInput.getText().toString().trim();
        if (host.isEmpty()) {
            status.setText("Masukkan IP HP penerima.");
            return;
        }

        sendButton.setEnabled(false);
        status.setText("Menghubungkan ke " + host + "...");

        new Thread(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, PORT), 10000);
                DataOutputStream out = new DataOutputStream(
                        new BufferedOutputStream(socket.getOutputStream()));

                ArrayList<DocumentFile> files = new ArrayList<>();
                ArrayList<String> paths = new ArrayList<>();
                collect(sendFolder, "", files, paths);

                out.writeInt(files.size());

                byte[] buffer = new byte[65536];
                long total = 0;

                for (int n = 0; n < files.size(); n++) {
                    DocumentFile f = files.get(n);
                    String path = paths.get(n);

                    out.writeUTF(path);
                    out.writeBoolean(f.isDirectory());

                    if (!f.isDirectory()) {
                        long size = f.length();
                        out.writeLong(size);

                        try (InputStream in =
                                getContentResolver().openInputStream(f.getUri())) {
                            if (in == null) throw new IOException("File tidak bisa dibaca");

                            int count;
                            while ((count = in.read(buffer)) != -1) {
                                out.write(buffer, 0, count);
                                total += count;
                            }
                        }
                    }

                    final int done = n + 1;
                    final long bytes = total;
                    ui(() -> status.setText("Mengirim " + done + "/"
                            + files.size() + " item\n"
                            + readable(bytes)));
                }

                out.flush();
                ui(() -> status.setText("Transfer selesai!"));
            } catch (Exception e) {
                ui(() -> status.setText("Gagal mengirim: " + e.getMessage()));
            } finally {
                ui(() -> sendButton.setEnabled(true));
            }
        }).start();
    }

    private void collect(DocumentFile folder, String prefix,
                         ArrayList<DocumentFile> files,
                         ArrayList<String> paths) {
        for (DocumentFile f : folder.listFiles()) {
            String path = prefix.isEmpty()
                    ? f.getName() : prefix + "/" + f.getName();

            files.add(f);
            paths.add(path);

            if (f.isDirectory()) {
                collect(f, path, files, paths);
            }
        }
    }

    private void startReceiver() {
        if (receiving) return;
        receiving = true;

        new Thread(() -> {
            try (ServerSocket server = new ServerSocket(PORT)) {
                ui(() -> status.setText("Menunggu pengirim di port "
                        + PORT + "\nPastikan kedua HP satu jaringan."));

                while (receiving) {
                    try (Socket socket = server.accept()) {
                        receiveData(socket);
                    }
                }
            } catch (Exception e) {
                ui(() -> status.setText("Penerima berhenti: " + e.getMessage()));
            } finally {
                receiving = false;
            }
        }).start();
    }

    private void receiveData(Socket socket) throws IOException {
        DataInputStream in = new DataInputStream(
                new BufferedInputStream(socket.getInputStream()));

        int count = in.readInt();
        if (count < 0 || count > 100000) {
            throw new IOException("Jumlah item tidak valid");
        }

        long total = 0;
        byte[] buffer = new byte[65536];

        for (int n = 0; n < count; n++) {
            String path = in.readUTF();
            boolean directory = in.readBoolean();

            if (path.startsWith("/") || path.contains("..")
                    || path.contains("\\") || path.isEmpty()) {
                throw new IOException("Nama/path tidak aman");
            }

            DocumentFile parent = destination;
            String[] parts = path.split("/");

            for (int p = 0; p < parts.length - 1; p++) {
                DocumentFile next = parent.findFile(parts[p]);
                if (next == null) next = parent.createDirectory(parts[p]);
                if (next == null || !next.isDirectory()) {
                    throw new IOException("Gagal membuat folder");
                }
                parent = next;
            }

            String name = parts[parts.length - 1];

            if (directory) {
                if (parent.findFile(name) == null) {
                    parent.createDirectory(name);
                }
            } else {
                long size = in.readLong();
                if (size < 0 || size > 20L * 1024 * 1024 * 1024) {
                    throw new IOException("Ukuran file tidak valid");
                }

                DocumentFile old = parent.findFile(name);
                if (old != null) old.delete();

                DocumentFile output = parent.createFile(
                        "application/octet-stream", name);
                if (output == null) throw new IOException("Gagal membuat file");

                try (OutputStream out =
                        getContentResolver().openOutputStream(output.getUri())) {
                    if (out == null) throw new IOException("Gagal membuka file");

                    long remaining = size;
                    while (remaining > 0) {
                        int wanted = (int) Math.min(buffer.length, remaining);
                        int read = in.read(buffer, 0, wanted);
                        if (read == -1) throw new EOFException("Transfer terputus");
                        out.write(buffer, 0, read);
                        remaining -= read;
                        total += read;
                    }
                }
            }

            final int done = n + 1;
            final long bytes = total;
            ui(() -> status.setText("Menerima " + done + "/"
                    + count + " item\n" + readable(bytes)));
        }

        ui(() -> status.setText("Folder berhasil diterima!"));
    }

    private String readable(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024)
            return String.format(Locale.US, "%.1f MB",
                    bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB",
                bytes / (1024.0 * 1024 * 1024));
    }

    @Override
    protected void onDestroy() {
        receiving = false;
        super.onDestroy();
    }
}
