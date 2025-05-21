package com.kmd.bussingswv2.ui.Home;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

import com.kmd.bussingswv2.R;

public class HomeFragment extends Fragment {

    private RecyclerView recyclerView;
    private ScannedTicketAdapter adapter;
    private ArrayList<ScannedTicketLists> scannedTickets;

    private TextView walletBalance, totalRevenue, passengerCount, scannedTicketAmount;
    private ImageView download;

    private FirebaseFirestore db;
    private FirebaseAuth auth;

    private int scannedPassengerCount = 0;
    private double totalTicketAmount = 0;

    private ListenerRegistration verifiedTicketsListener;
    private ListenerRegistration ticketGeneratedListener;

    private SwipeRefreshLayout swipeRefreshLayout;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        View root = inflater.inflate(R.layout.fragment_home, container, false);

        recyclerView = root.findViewById(R.id.scannedTicketRecycler);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        scannedTickets = new ArrayList<>();
        adapter = new ScannedTicketAdapter(getContext(), scannedTickets);
        recyclerView.setAdapter(adapter);

        walletBalance = root.findViewById(R.id.walletBalance);
        totalRevenue = root.findViewById(R.id.totalRevenue);
        passengerCount = root.findViewById(R.id.passengerCount);
        scannedTicketAmount = root.findViewById(R.id.scannedTicketAmount);
        download = root.findViewById(R.id.download);

        swipeRefreshLayout = root.findViewById(R.id.swipeRefreshLayout);

        db = FirebaseFirestore.getInstance();
        auth = FirebaseAuth.getInstance();

        swipeRefreshLayout.setOnRefreshListener(() -> {
            refreshData();
        });

        // Set up download button click listener
        download.setOnClickListener(v -> {
            String data = "Scanned Passengers: " + scannedPassengerCount + "\n" +
                    "Total Amount: ₱" + String.format("%.2f", totalTicketAmount);

            // Save data to a .txt file
            saveDataToPdf();
        });

        return root;
    }

    private void refreshData() {
        if (auth.getCurrentUser() == null) return;
        String currentDriverUid = auth.getCurrentUser().getUid();

        if (verifiedTicketsListener != null) verifiedTicketsListener.remove();
        if (ticketGeneratedListener != null) ticketGeneratedListener.remove();

        verifiedTicketsListener = db.collection("VerifiedTicketsCollection")
                .whereEqualTo("uid", currentDriverUid)
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null) {
                        Log.e("Firestore", "VerifiedTickets listen failed.", error);
                        return;
                    }

                    if (snapshots != null) {
                        scannedTickets.clear();
                        scannedPassengerCount = 0;
                        totalTicketAmount = 0;

                        String todayDate = new SimpleDateFormat("ddMMMMyyyy", Locale.getDefault())
                                .format(Calendar.getInstance().getTime());

                        double todayTotalPrice = 0;
                        int todayScannedCount = 0;

                        for (QueryDocumentSnapshot doc : snapshots) {
                            String ticketCode = doc.getId();
                            Map<String, Object> ticketData = doc.getData();

                            // Get price safely
                            Object priceObj = ticketData.get("price");
                            String priceStr = priceObj != null ? priceObj.toString() : "0";
                            double price = 0;
                            try {
                                price = Double.parseDouble(priceStr);
                            } catch (Exception e) {
                                Log.w("ParsePrice", "Failed to parse price: " + priceStr);
                            }

                            totalTicketAmount += price;
                            scannedPassengerCount++;

                            String bookingDate = (String) ticketData.get("bookingDate");
                            SimpleDateFormat dbFormat = new SimpleDateFormat("MM/dd/yyyy", Locale.getDefault());
                            SimpleDateFormat compareFormat = new SimpleDateFormat("ddMMMMyyyy", Locale.getDefault());

                            if (bookingDate != null) {
                                Log.d("BookingDateCheck", "Raw bookingDate: " + bookingDate);
                                try {
                                    Date parsedBookingDate = dbFormat.parse(bookingDate);
                                    String normalizedBookingDate = compareFormat.format(parsedBookingDate);
                                    Log.d("BookingDateCheck", "Normalized date: " + normalizedBookingDate + " | Today: " + todayDate);

                                    if (todayDate.equals(normalizedBookingDate)) {
                                        todayTotalPrice += price;
                                        todayScannedCount++;
                                        Log.d("TicketCount", "Ticket counted for today: " + ticketCode + ", Price: " + priceStr);
                                    } else {
                                        Log.d("TicketCount", "Ticket NOT counted (not today): " + ticketCode);
                                    }

                                } catch (ParseException e) {
                                    Log.w("DateParse", "Failed to parse bookingDate: " + bookingDate, e);
                                }
                            } else {
                                Log.w("BookingDateMissing", "bookingDate is null for ticket: " + ticketCode);
                            }

                            // Add ticket to the list
                            ScannedTicketLists ticket = new ScannedTicketLists(
                                    (String) ticketData.get("from"),
                                    (String) ticketData.get("to"),
                                    bookingDate,
                                    (String) ticketData.get("bookingTime"),
                                    (String) ticketData.get("passenger"),
                                    (String) ticketData.get("discount"),
                                    ticketCode,
                                    priceStr
                            );

                            scannedTickets.add(ticket);
                        }

                        Log.d("FinalStats", "Total tickets: " + scannedPassengerCount + ", Total amount: " + totalTicketAmount);
                        Log.d("FinalStats", "Today's tickets: " + todayScannedCount + ", Today's amount: " + todayTotalPrice);

                        adapter.notifyDataSetChanged();
                        walletBalance.setText("₱" + String.format("%.2f", totalTicketAmount));
                        passengerCount.setText(String.valueOf(todayScannedCount));
                        scannedTicketAmount.setText("₱" + String.format("%.2f", todayTotalPrice));

                        swipeRefreshLayout.setRefreshing(false);

                    }
                });

        ticketGeneratedListener = db.collection("BussingPaymentsCollection")
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null) {
                        Log.e("Firestore", "TicketGenerated listen failed.", error);
                        return;
                    }

                    if (snapshots != null) {
                        double totalRevenueEarned = 0;

                        for (QueryDocumentSnapshot doc : snapshots) {
                            Object priceObj = doc.get("amountPaid");

                            if (priceObj instanceof Number) {
                                totalRevenueEarned += ((Number) priceObj).doubleValue();
                            } else if (priceObj instanceof String) {
                                try {
                                    totalRevenueEarned += Double.parseDouble((String) priceObj);
                                } catch (NumberFormatException e) {
                                    Log.w("ParsePrice", "Invalid price string: " + priceObj);
                                }
                            }
                        }

                        totalRevenue.setText("₱" + String.format("%.2f", totalRevenueEarned));

                        swipeRefreshLayout.setRefreshing(false);

                    }
                });
    }


    @Override
    public void onStart() {
        super.onStart();
        refreshData();
    }

    @Override
    public void onStop() {
        super.onStop();
        if (verifiedTicketsListener != null) {
            verifiedTicketsListener.remove();
            verifiedTicketsListener = null;
        }
        if (ticketGeneratedListener != null) {
            ticketGeneratedListener.remove();
            ticketGeneratedListener = null;
        }
    }

    private void saveDataToPdf() {
        try {
            String date = new SimpleDateFormat("ddMMMMyyyy", Locale.getDefault()).format(Calendar.getInstance().getTime());

            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!downloadsDir.exists()) downloadsDir.mkdirs();

            // Delete old files for today (tickets_*.pdf with today's date prefix)
            File[] files = downloadsDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().startsWith("tickets_" + date) && f.getName().endsWith(".pdf")) {
                        boolean deleted = f.delete();
                        Log.d("PDFSave", "Deleted old file " + f.getName() + ": " + deleted);
                    }
                }
            }

            // Create unique new filename (should not exist)
            File file;
            int version = 1;
            do {
                String filename = version == 1 ? "tickets_" + date + ".pdf" : "tickets_" + date + "_v" + version + ".pdf";
                file = new File(downloadsDir, filename);
                version++;
            } while (file.exists());

            // Just in case (should not exist), delete before writing
            if (file.exists()) {
                boolean deleted = file.delete();
                Log.d("PDFSave", "Deleted existing file before writing: " + deleted);
            }

            PdfDocument pdfDocument = new PdfDocument();
            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(595, 842, 1).create(); // A4 size
            PdfDocument.Page page = pdfDocument.startPage(pageInfo);
            Canvas canvas = page.getCanvas();

            int x = 40;
            int y = 60;

            // Draw Logo centered
            Bitmap busBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.bussing_logo_png);
            Bitmap scaledBitmap = Bitmap.createScaledBitmap(busBitmap, 80, 80, false);
            canvas.drawBitmap(scaledBitmap, (595 - 80) / 2, y, null);
            y += 90;

            // Title "Bussing"
            Paint titlePaint = new Paint();
            titlePaint.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD));
            titlePaint.setTextSize(24);
            titlePaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Bussing", 297, y, titlePaint);
            y += 30;

            // Subtitle with date
            Paint subtitlePaint = new Paint();
            subtitlePaint.setTextSize(16);
            subtitlePaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Scanned Tickets as of " + date, 297, y, subtitlePaint);
            y += 40;

            // Header Row
            Paint headerPaint = new Paint();
            headerPaint.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD));
            headerPaint.setTextSize(14);
            headerPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("From", x, y, headerPaint);
            canvas.drawText("To", x + 100, y, headerPaint);
            canvas.drawText("Passenger", x + 200, y, headerPaint);
            canvas.drawText("Price", x + 400, y, headerPaint);
            y += 20;

            Paint linePaint = new Paint();
            linePaint.setColor(Color.BLACK);
            canvas.drawLine(x, y, 550, y, linePaint);
            y += 10;

            Paint textPaint = new Paint();
            textPaint.setTextSize(12);
            textPaint.setTextAlign(Paint.Align.LEFT);

            double totalPriceToday = 0;
            int todayTicketCount = 0;

            String todayDate = new SimpleDateFormat("ddMMMMyyyy", Locale.getDefault()).format(Calendar.getInstance().getTime());
            SimpleDateFormat dbFormat = new SimpleDateFormat("MM/dd/yyyy", Locale.getDefault());
            SimpleDateFormat compareFormat = new SimpleDateFormat("ddMMMMyyyy", Locale.getDefault());

            for (ScannedTicketLists ticket : scannedTickets) {
                if (y > 800) { // New page if too long
                    pdfDocument.finishPage(page);
                    page = pdfDocument.startPage(pageInfo);
                    canvas = page.getCanvas();
                    y = 60;
                }

                String bookingDate = ticket.getBookingDate();
                if (bookingDate != null) {
                    try {
                        Date parsedDate = dbFormat.parse(bookingDate);
                        String normalizedDate = compareFormat.format(parsedDate);

                        if (todayDate.equals(normalizedDate)) {
                            // Draw From and To with multiline support
                            drawMultilineText(canvas, ticket.getTicketFrom(), x, y, textPaint, 14);
                            drawMultilineText(canvas, ticket.getTicketTo(), x + 100, y, textPaint, 14);

                            // Draw Passenger and Price in single line
                            canvas.drawText(ticket.getPassengerType(), x + 200, y, textPaint);
                            canvas.drawText("P" + ticket.getTicketPrice(), x + 400, y, textPaint);

                            int fromLines = ticket.getTicketFrom() == null ? 0 : ticket.getTicketFrom().split(" ").length;
                            int toLines = ticket.getTicketTo() == null ? 0 : ticket.getTicketTo().split(" ").length;
                            int maxLines = Math.max(fromLines, toLines);

                            y += maxLines * 14;  // move y by number of lines used
                            totalPriceToday += Double.parseDouble(ticket.getTicketPrice());
                            todayTicketCount++;
                        }
                    } catch (ParseException e) {
                        Log.e("DateParse", "Failed to parse date", e);
                    }
                }
            }

            y += 20;
            canvas.drawLine(x, y, 550, y, linePaint);
            y += 20;
            canvas.drawText("Total Tickets Scanned Today: " + todayTicketCount, x, y, textPaint);
            y += 20;
            canvas.drawText("Total Price: P" + String.format("%.2f", totalPriceToday), x, y, textPaint);

            pdfDocument.finishPage(page);

            Log.d("PDFSave", "Attempting to write PDF to: " + file.getAbsolutePath());

            // Write pdf to file safely, no append
            FileOutputStream fos = new FileOutputStream(file, false);
            pdfDocument.writeTo(fos);
            pdfDocument.close();
            fos.close();

            Toast.makeText(getContext(), "PDF saved as " + file.getName(), Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Log.e("PDFSave", "Failed to save PDF. Check storage permission or path.", e);
            Toast.makeText(getContext(), "Failed to save PDF", Toast.LENGTH_SHORT).show();
        }
    }

    private void drawMultilineText(Canvas canvas, String text, int x, int y, Paint paint, int lineHeight) {
        if (text == null) return;
        String[] parts = text.split(" ");
        for (int i = 0; i < parts.length; i++) {
            canvas.drawText(parts[i], x, y + i * lineHeight, paint);
        }
    }

}