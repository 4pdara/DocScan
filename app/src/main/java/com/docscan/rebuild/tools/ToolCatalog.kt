package com.docscan.rebuild.tools

import com.docscan.rebuild.R

enum class ToolGroup(val title: Int) { SCAN(R.string.group_scan), IMPORT(R.string.group_import), CONVERT(R.string.group_convert), EDIT(R.string.group_edit), HELP(R.string.group_help) }
enum class ScanTool(val group: ToolGroup) {
    ID_CARD(ToolGroup.SCAN), OCR(ToolGroup.SCAN), ID_PHOTO(ToolGroup.SCAN), FORMULA(ToolGroup.SCAN),
    TRANSLATE(ToolGroup.SCAN), BOOK(ToolGroup.SCAN), SLIDES(ToolGroup.SCAN), WHITEBOARD(ToolGroup.SCAN), TIMESTAMP(ToolGroup.SCAN),
    IMPORT_IMAGES(ToolGroup.IMPORT), IMPORT_FILES(ToolGroup.IMPORT),
    WORD(ToolGroup.CONVERT), EXCEL(ToolGroup.CONVERT), POWERPOINT(ToolGroup.CONVERT), PDF_IMAGES(ToolGroup.CONVERT), LONG_IMAGE(ToolGroup.CONVERT),
    SIGNATURE(ToolGroup.EDIT), WATERMARK(ToolGroup.EDIT), SMART_ERASE(ToolGroup.EDIT), REMOVE_MARKS(ToolGroup.EDIT),
    RESTORE_PHOTO(ToolGroup.EDIT), MERGE(ToolGroup.EDIT), EXTRACT(ToolGroup.EDIT), REORDER(ToolGroup.EDIT), LOCK(ToolGroup.EDIT),
    SOLVER(ToolGroup.HELP), COUNT(ToolGroup.HELP), PRINT(ToolGroup.HELP), BARCODE(ToolGroup.HELP);
    val label: Int get() = when(this) {
        ID_CARD->R.string.tool_id_card; OCR->R.string.ocr; ID_PHOTO->R.string.tool_id_photo;FORMULA->R.string.tool_formula
        TRANSLATE->R.string.tool_translate;BOOK->R.string.tool_book;SLIDES->R.string.tool_slides;WHITEBOARD->R.string.tool_whiteboard;TIMESTAMP->R.string.tool_timestamp
        IMPORT_IMAGES->R.string.import_images;IMPORT_FILES->R.string.tool_import_files;WORD->R.string.tool_word;EXCEL->R.string.tool_excel;POWERPOINT->R.string.tool_powerpoint
        PDF_IMAGES->R.string.tool_pdf_images;LONG_IMAGE->R.string.tool_long_image;SIGNATURE->R.string.tool_signature;WATERMARK->R.string.tool_watermark;SMART_ERASE->R.string.tool_smart_erase
        REMOVE_MARKS->R.string.tool_remove_marks;RESTORE_PHOTO->R.string.tool_restore_photo;MERGE->R.string.tool_merge;EXTRACT->R.string.tool_extract;REORDER->R.string.tool_reorder;LOCK->R.string.tool_lock
        SOLVER->R.string.tool_solver;COUNT->R.string.tool_count;PRINT->R.string.tool_print;BARCODE->R.string.tool_barcode
    }
    val hint: Int get() = when(this) {
        WORD->R.string.hint_word;EXCEL->R.string.hint_excel;POWERPOINT->R.string.hint_powerpoint;LONG_IMAGE->R.string.hint_long_image
        SIGNATURE->R.string.hint_signature;SMART_ERASE->R.string.hint_erase;REMOVE_MARKS->R.string.hint_marks;RESTORE_PHOTO->R.string.hint_restore
        TRANSLATE,FORMULA,SOLVER->R.string.hint_ai;COUNT->R.string.hint_count;LOCK->R.string.hint_lock;IMPORT_FILES->R.string.hint_import_pdf
        ID_CARD->R.string.hint_id;ID_PHOTO->R.string.hint_id_photo;BOOK->R.string.hint_book;SLIDES->R.string.hint_slides;WHITEBOARD->R.string.hint_whiteboard
        else->R.string.hint_choose_document
    }
}
