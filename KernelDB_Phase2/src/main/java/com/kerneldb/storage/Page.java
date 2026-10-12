package com.kerneldb.storage;

import com.kerneldb.model.Student;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Fixed-size database page. The first 16 bytes are a page header; the remainder
 * contains 31 fixed-size record slots. This is a deliberately small teaching format.
 */
public final class Page {
    public static final int PAGE_SIZE = 4096;
    public static final int HEADER_SIZE = 16;
    public static final int SLOT_SIZE = 128;
    public static final int SLOTS_PER_PAGE = (PAGE_SIZE - HEADER_SIZE) / SLOT_SIZE;
    private static final int MAGIC = 0x4B444231; // "KDB1"

    private final int pageNumber;
    private final Student[] slots = new Student[SLOTS_PER_PAGE];

    private Page(int pageNumber) {
        if (pageNumber < 0) throw new IllegalArgumentException("Page number cannot be negative.");
        this.pageNumber = pageNumber;
    }

    public static Page empty(int pageNumber) {
        return new Page(pageNumber);
    }

    public int pageNumber() { return pageNumber; }

    public Student getRecord(int slotNumber) {
        checkSlot(slotNumber);
        return slots[slotNumber];
    }

    public void setRecord(int slotNumber, Student student) {
        checkSlot(slotNumber);
        slots[slotNumber] = student;
    }

    public int findEmptySlotFrom(int startSlot) {
        int start = Math.max(0, startSlot);
        for (int i = start; i < slots.length; i++) {
            if (slots[i] == null) return i;
        }
        return -1;
    }

    public int slotOfStudent(int studentId) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null && slots[i].id() == studentId) return i;
        }
        return -1;
    }

    public int recordCount() {
        int count = 0;
        for (Student student : slots) if (student != null) count++;
        return count;
    }

    public List<Student> records() {
        List<Student> result = new ArrayList<>();
        for (Student student : slots) if (student != null) result.add(student);
        return result;
    }

    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(PAGE_SIZE).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(MAGIC);
        buffer.putInt(pageNumber);
        buffer.putInt(recordCount());
        buffer.putInt(SLOT_SIZE);

        for (Student student : slots) {
            int slotStart = buffer.position();
            if (student == null) {
                buffer.putInt(0);
                buffer.position(slotStart + SLOT_SIZE);
                continue;
            }
            byte[] name = student.name().getBytes(StandardCharsets.UTF_8);
            byte[] course = student.course().getBytes(StandardCharsets.UTF_8);
            buffer.putInt(student.id());
            buffer.putInt(name.length);
            putFixed(buffer, name, Student.MAX_NAME_BYTES);
            buffer.putInt(course.length);
            putFixed(buffer, course, Student.MAX_COURSE_BYTES);
            buffer.putDouble(student.marks());
            buffer.putDouble(student.attendance());
            buffer.position(slotStart + SLOT_SIZE);
        }
        return buffer.array();
    }

    public static Page fromBytes(int expectedPageNumber, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length != PAGE_SIZE) {
            throw new IOException("Invalid page size for page " + expectedPageNumber + ".");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        int magic = buffer.getInt();
        int storedPageNumber = buffer.getInt();
        int storedRecordCount = buffer.getInt();
        int storedSlotSize = buffer.getInt();
        if (magic != MAGIC || storedPageNumber != expectedPageNumber || storedSlotSize != SLOT_SIZE) {
            throw new IOException("Invalid or corrupt page header at page " + expectedPageNumber + ".");
        }

        Page page = new Page(expectedPageNumber);
        for (int i = 0; i < SLOTS_PER_PAGE; i++) {
            int slotStart = buffer.position();
            int id = buffer.getInt();
            if (id > 0) {
                int nameLength = buffer.getInt();
                byte[] nameBytes = getFixed(buffer, Student.MAX_NAME_BYTES);
                int courseLength = buffer.getInt();
                byte[] courseBytes = getFixed(buffer, Student.MAX_COURSE_BYTES);
                double marks = buffer.getDouble();
                double attendance = buffer.getDouble();
                if (nameLength < 1 || nameLength > nameBytes.length
                        || courseLength < 1 || courseLength > courseBytes.length) {
                    throw new IOException("Invalid record length in page " + expectedPageNumber + ".");
                }
                String name = new String(nameBytes, 0, nameLength, StandardCharsets.UTF_8);
                String course = new String(courseBytes, 0, courseLength, StandardCharsets.UTF_8);
                try {
                    page.slots[i] = new Student(id, name, course, marks, attendance);
                } catch (IllegalArgumentException ex) {
                    throw new IOException("Invalid record in page " + expectedPageNumber + ": " + ex.getMessage(), ex);
                }
            }
            buffer.position(slotStart + SLOT_SIZE);
        }
        if (page.recordCount() != storedRecordCount) {
            throw new IOException("Record-count mismatch in page " + expectedPageNumber + ".");
        }
        return page;
    }

    private static void putFixed(ByteBuffer buffer, byte[] source, int width) {
        buffer.put(source);
        for (int i = source.length; i < width; i++) buffer.put((byte) 0);
    }

    private static byte[] getFixed(ByteBuffer buffer, int width) {
        byte[] result = new byte[width];
        buffer.get(result);
        return result;
    }

    private static void checkSlot(int slotNumber) {
        if (slotNumber < 0 || slotNumber >= SLOTS_PER_PAGE) {
            throw new IndexOutOfBoundsException("Slot must be between 0 and " + (SLOTS_PER_PAGE - 1) + ".");
        }
    }
}
